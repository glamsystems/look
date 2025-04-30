package systems.glam.look.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import software.sava.core.accounts.PublicKey;
import software.sava.core.crypto.Hash;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.glam.look.LookupTableDiscoveryService;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static software.sava.services.jetty.handlers.HandlerUtil.JSON_CONTENT;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class TableRecommendationHandler extends LookupTableDiscoveryServiceHandler {

  private static final byte[] NOT_SIGNED = new byte[Transaction.SIGNATURE_LENGTH];

  static {
    Arrays.fill(NOT_SIGNED, (byte) 0);
  }

  public TableRecommendationHandler(final LookupTableDiscoveryService tableService,
                                    final LookupTableCache tableCache,
                                    final RpcCaller rpcCaller) {
    super(Invocable.InvocationType.BLOCKING, tableService, tableCache, rpcCaller);
  }

  private static JsonIterator readJsonBody(final Request request) throws IOException {
    try (final var is = Content.Source.asInputStream(request)) {
      final var body = is.readAllBytes();
      return body.length > 0 ? JsonIterator.parse(body) : null;
    }
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    super.handle(request, response, callback);

    try (final var ji = readJsonBody(request)) {
      if (ji == null) {
        response.setStatus(400);
        response.getHeaders().put(JSON_CONTENT);
        Content.Sink.write(response, true, """
            {"msg": "Must provide request body."}""", callback
        );
        return true;
      }

      final var parser = new Parser();
      ji.testObject(parser);
      final int numTransactions = parser.numTransactions();
      final var transactionAccountsMap = HashMap.<String, TransactionAccounts>newHashMap(numTransactions);

      final var signatures = parser.signatures;
      final var transactionDataFutures = transactionDataFutures(rpcCaller, signatures);

      final var dataList = parser.data;
      if (dataList != null) {
        for (final var data : dataList) {
          final String id;
          if (Arrays.equals(data, 1, 65, NOT_SIGNED, 0, Transaction.SIGNATURE_LENGTH)) {
            id = new String(Hash.sha256(data));
          } else {
            id = Base58.encode(data, 1, 65);
          }
          final var transactionAccounts = parseTransactionAccounts(id, data);
          if (transactionAccounts == null) {
            writeUnableToCreateTxResponse(response, callback);
            return true;
          }
          transactionAccountsMap.put(id, transactionAccounts);
        }
      }

      for (final var transactionDataFuture : transactionDataFutures) {
        final var transactionAccounts = transactionDataFuture.join();
        if (transactionAccounts == null) {
          writeUnableToCreateTxResponse(response, callback);
          return true;
        }
        transactionAccountsMap.put(transactionAccounts.id(), transactionAccounts);
      }

      if (transactionAccountsMap.size() != numTransactions) {
        writeUnableToCreateTxResponse(response, callback);
        return true;
      }

      final var jsonResponse = new StringBuilder(1024);
      jsonResponse.append('{').append('\n');
      final var sharedMustBeIncludedKeys = HashSet.<PublicKey>newHashSet(64);
      for (final var transactionAccounts : transactionAccountsMap.values()) {
        final var eligibleKeys = transactionAccounts.aggregateMustBeIncludedKeys(sharedMustBeIncludedKeys);
        final var eligibleJson = eligibleKeys.stream()
            .map(PublicKey::toBase58)
            .collect(Collectors.joining("\",\""));
        final var indexedKeys = transactionAccounts.indexedAccountKeys();
        final var indexedJson = indexedKeys.stream()
            .map(PublicKey::toBase58)
            .collect(Collectors.joining("\",\""));
        jsonResponse.append(String.format("""
                "%s": {
                  "eligible": ["%s"],
                  "indexed": ["%s"],
                  "numEligibleNotIndexed": %d,
                },
                """,
            transactionAccounts.id(),
            eligibleJson,
            indexedJson,
            eligibleKeys.size() - indexedKeys.size()
        ));
      }

      final var eligibleCounts = HashMap.<PublicKey, TransactionAccounts.Scores>newHashMap(64);
      for (final var transactionAccounts : transactionAccountsMap.values()) {
        transactionAccounts.tableEligibleAccounts(sharedMustBeIncludedKeys, eligibleCounts);
      }

      final var eligibleKeyList = List.copyOf(eligibleCounts.keySet());
      final int numEligible = eligibleKeyList.size();
      final var eligibleAccountOwners = HashMap.<PublicKey, PublicKey>newHashMap(numEligible);
      for (int from = 0, to; from < numEligible; from += SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS) {
        to = Math.min(numEligible, from + SolanaRpcClient.MAX_MULTIPLE_ACCOUNTS);
        final var batch = eligibleKeyList.subList(from, to);
        final var eligibleAccounts = rpcCaller.courteousGet(
            rpcClient -> rpcClient.getAccounts(batch),
            "rpcClient::getEligibleAccounts"
        );
        for (final var accountInfo : eligibleAccounts) {
          if (accountInfo != null) {
            eligibleAccountOwners.put(accountInfo.pubKey(), accountInfo.owner());
          }
        }
      }

      final var json = eligibleCounts.entrySet().stream().map(entry -> {
        final var key = entry.getKey();
        final var scores = entry.getValue();
        return String.format("""
                "%s": {
                  "eligibleScore": %d,
                  "notIndexedScore": %d,
                  "owner": "%s"
                }""",
            key,
            scores.eligibleScore,
            scores.notIndexedScore,
            eligibleAccountOwners.get(key)
        );
      }).collect(Collectors.joining(","));
      jsonResponse.append("""
          "scores": {""");
      jsonResponse.append(json).append("}\n}");

      response.getHeaders().put(JSON_CONTENT);
      Content.Sink.write(response, true, jsonResponse.toString(), callback);
      return true;
    } catch (final IOException ioException) {
      response.setStatus(400);
      response.getHeaders().put(JSON_CONTENT);
      Content.Sink.write(response, true, """
          {"msg": "Failed to read request body."}""", callback
      );
      return true;
    }
  }

  private List<CompletableFuture<TransactionAccounts>> transactionDataFutures(final RpcCaller rpcCaller,
                                                                              final Set<String> signatures) {
    if (signatures != null) {
      final int numSignatures = signatures.size();
      if (numSignatures > 0) {
        final var transactionDataFutures = new ArrayList<CompletableFuture<TransactionAccounts>>(numSignatures);
        for (final var signature : signatures) {
          final var transactionDataFuture = rpcCaller.courteousCall(
              rpcClient -> rpcClient.getTransaction(signature),
              "rpcClient::getTransaction"
          ).thenApply(tx -> parseTransactionAccounts(signature, tx.data()));
          transactionDataFutures.add(transactionDataFuture);
        }
        return transactionDataFutures;
      }
    }
    return List.of();
  }

  private static void writeUnableToCreateTxResponse(final Response response, final Callback callback) {
    response.setStatus(400);
    response.getHeaders().put(JSON_CONTENT);
    Content.Sink.write(
        response, true, """
            {
              "msg": "Unable to construct all transactions."
            }"""
        , callback
    );
  }

  private static final class Parser implements FieldBufferPredicate {

    private Set<String> signatures;
    private List<byte[]> data;

    int numTransactions() {
      return (signatures == null ? 0 : signatures.size()) + (data == null ? 0 : data.size());
    }


    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("signatures", buf, offset, len)) {
        signatures = new HashSet<>();
        while (ji.readArray()) {
          signatures.add(ji.readString());
        }
      } else if (fieldEquals("data", buf, offset, len)) {
        data = new ArrayList<>();
        while (ji.readArray()) {
          data.add(ji.decodeBase64String());
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
