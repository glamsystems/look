package systems.glam.look.http;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.services.jetty.handlers.BaseJettyHandler;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.glam.look.LookupTableDiscoveryService;

import java.util.*;

import static software.sava.services.jetty.handlers.HandlerUtil.ALLOW_POST;

abstract class LookupTableDiscoveryServiceHandler extends BaseJettyHandler {

  protected final LookupTableDiscoveryService tableService;
  protected final LookupTableCache tableCache;
  protected final RpcCaller rpcCaller;

  LookupTableDiscoveryServiceHandler(final InvocationType invocationType,
                                     final LookupTableDiscoveryService tableService,
                                     final LookupTableCache tableCache,
                                     final RpcCaller rpcCaller) {
    super(invocationType, ALLOW_POST);
    this.tableService = tableService;
    this.tableCache = tableCache;
    this.rpcCaller = rpcCaller;
  }

  protected final ByteEncoding getEncoding(final Request request,
                                           final Response response,
                                           final Callback callback) {
    final var headers = request.getHeaders();
    final var encoding = headers.get("X-BYTE-ENCODING");
    if (encoding == null || encoding.isEmpty()) {
      return ByteEncoding.base64;
    } else {
      try {
        return ByteEncoding.valueOf(encoding);
      } catch (final RuntimeException ex) {
        response.setStatus(415);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        Content.Sink.write(response, true, """
            {
              "msg": "Invalid X-BYTE-ENCODING."
            }""", callback
        );
        return null;
      }
    }
  }

  protected final TransactionAccounts parseTransactionAccounts(final String id, final byte[] data) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);

    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseAccounts();
      return new TransactionAccounts(id, skeleton, accounts, Set.of());
    }

    final int version = skeleton.version();
    if (version != 0) {
      return null;
    } else {
      final var lookupTableAccounts = skeleton.lookupTableAccounts();
      final int numTableAccounts = lookupTableAccounts.length;
      final var lookupTables = HashMap.<PublicKey, AddressLookupTable>newHashMap(numTableAccounts);
      List<PublicKey> notCached = null;
      for (final var key : lookupTableAccounts) {
        var lookupTable = tableCache.getTable(key);
        if (lookupTable == null) {
          lookupTable = tableService.scanForTable(key);
          if (lookupTable == null) {
            if (notCached == null) {
              notCached = new ArrayList<>(numTableAccounts);
            }
            notCached.add(key);
          } else {
            lookupTable = lookupTable.withReverseLookup();
            tableCache.mergeTable(0, lookupTable);
            lookupTables.put(lookupTable.address(), lookupTable);
          }
        } else {
          lookupTables.put(lookupTable.address(), lookupTable);
        }
      }

      if (notCached != null) {
        if (notCached.size() == 1) {
          final var table = tableCache.getOrFetchTable(notCached.getFirst());
          lookupTables.put(table.address(), table);
        } else {
          final var tables = tableCache.getOrFetchTables(notCached);
          for (final var lookupTableAccountMeta : tables) {
            final var table = lookupTableAccountMeta.lookupTable();
            lookupTables.put(table.address(), table);
          }
        }
        if (lookupTables.size() != numTableAccounts) {
          for (final var key : lookupTableAccounts) {
            if (!lookupTables.containsKey(key)) {
              return null;
            }
          }
        }
      }

      final var accounts = skeleton.parseAccounts(lookupTables);
      final var indexedAccounts = HashSet.<PublicKey>newHashSet(skeleton.numIndexedAccounts());
      for (int i = skeleton.numIncludedAccounts(); i < accounts.length; ++i) {
        indexedAccounts.add(accounts[i].publicKey());
      }
      return new TransactionAccounts(id, skeleton, accounts, indexedAccounts);
    }
  }
}
