package systems.glam.look.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.PublicKeyEncoding;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.comodal.jsoniter.JsonIterator;
import systems.glam.look.LookupTableDiscoveryService;

import java.io.IOException;
import java.util.HashSet;

import static software.sava.services.jetty.handlers.HandlerUtil.JSON_CONTENT;

final class FromAccountsHandler extends DiscoverTablesHandler {

  private static final int MAX_BODY_LENGTH = (Transaction.MAX_ACCOUNTS * PublicKey.PUBLIC_KEY_LENGTH) << 1;

  FromAccountsHandler(final LookupTableDiscoveryService tableService,
                      final LookupTableCache tableCache,
                      final RpcCaller rpcCaller) {
    super(InvocationType.NON_BLOCKING, tableService, tableCache, rpcCaller);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final long startExchange = System.currentTimeMillis();
    super.setResponseHeaders(response);
    final var queryParams = queryParams(request);

    try (final var is = Content.Source.asInputStream(request)) {
      final var body = is.readAllBytes();
      final var ji = JsonIterator.parse(body);
      final var distinctAccounts = HashSet.<PublicKey>newHashSet(Transaction.MAX_ACCOUNTS);
      while (ji.readArray()) {
        distinctAccounts.add(PublicKeyEncoding.parseBase58Encoded(ji));
      }

      final long start = System.currentTimeMillis();
      final var lookupTables = queryParams.reRank()
          ? tableService.discoverTablesWithReRank(distinctAccounts)
          : tableService.discoverTables(distinctAccounts);
      writeResponse(response, callback, startExchange, queryParams, start, lookupTables);
      return true;
    } catch (final IOException ex) {
      response.setStatus(400);
      response.getHeaders().put(JSON_CONTENT);
      Content.Sink.write(response, true, """
          {"msg": "Failed to read request body."}""", callback
      );
      return true;
    }
  }
}
