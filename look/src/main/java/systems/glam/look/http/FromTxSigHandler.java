package systems.glam.look.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.glam.look.LookupTableDiscoveryService;

import java.io.IOException;

import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

final class FromTxSigHandler extends FromRawTxHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromTxSigHandler(final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache,
                   final RpcCaller rpcCaller) {
    super(InvocationType.BLOCKING, tableService, tableCache, rpcCaller);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final long startExchange = System.currentTimeMillis();
    super.setResponseHeaders(response);
    try {
      final var txSig = Content.Source.asString(request);
      try {
        final var tx = rpcCaller.courteousGet(
            rpcClient -> rpcClient.getTransaction(CONFIRMED, txSig),
            CallContext.DEFAULT_CALL_CONTEXT,
            "rpcClient::getTransaction"
        );
        // System.out.println(Base64.getEncoder().encodeToString(tx.data()));
        handle(request, response, callback, startExchange, tx);
      } catch (final RuntimeException ex) {
        logger.log(System.Logger.Level.ERROR, "Failed to process request " + txSig, ex);
        response.setStatus(500);
        callback.failed(ex);
      }
    } catch (final IOException ioException) {
      logger.log(System.Logger.Level.ERROR, "Failed to read request.", ioException);
      response.setStatus(500);
      callback.failed(ioException);
    }
    return true;
  }
}
