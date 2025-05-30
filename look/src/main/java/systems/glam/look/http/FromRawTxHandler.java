package systems.glam.look.http;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.response.Tx;
import software.sava.services.solana.alt.LookupTableCache;
import software.sava.services.solana.remote.call.RpcCaller;
import systems.glam.look.LookupTableDiscoveryService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static software.sava.services.jetty.handlers.HandlerUtil.JSON_CONTENT;

class FromRawTxHandler extends DiscoverTablesHandler {

  private static final System.Logger logger = System.getLogger(FromRawTxHandler.class.getName());

  FromRawTxHandler(final InvocationType invocationType,
                   final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache,
                   final RpcCaller rpcCaller) {
    super(invocationType, tableService, tableCache, rpcCaller);
  }

  FromRawTxHandler(final LookupTableDiscoveryService tableService,
                   final LookupTableCache tableCache,
                   final RpcCaller rpcCaller) {
    this(InvocationType.EITHER, tableService, tableCache, rpcCaller);
  }

  record TxStats(Set<PublicKey> eligible,
                 int inNetIndexed,
                 int outNetIndexed,
                 int inTxLength,
                 int outTxLength,
                 int delta,
                 List<TableStats> tableStats) {

    private static final List<TableStats> NONE_FOUND = List.of();

    static TxStats noneFound(final Set<PublicKey> eligible, final int inNetIndexed, final int inTxLength) {
      return new TxStats(
          eligible,
          inNetIndexed,
          0,
          inTxLength,
          inTxLength,
          0,
          NONE_FOUND
      );
    }

    static TxStats createStats(final Set<PublicKey> eligible,
                               final int inNetIndexed,
                               final Set<PublicKey> indexed,
                               final List<TableStats> tableStatsList,
                               final byte[] oldTxData,
                               final byte[] newTxData) {
      return new TxStats(
          eligible,
          inNetIndexed,
          indexed.size(),
          oldTxData.length,
          newTxData.length,
          oldTxData.length - newTxData.length,
          tableStatsList
      );
    }

    String toJson() {
      return String.format(
          """
              {
                "eligible": ["%s"],
                "inNetIndexed": %d,
                "outNetIndexed": %d,
                "inTxLength": %d,
                "outTxLength": %d,
                "delta": %d,
                "tableStats": [
                %s
                ]
              }""",
          eligible.stream().map(PublicKey::toBase58).collect(Collectors.joining("\",\"")),
          inNetIndexed, outNetIndexed,
          inTxLength, outTxLength,
          delta,
          tableStats.stream()
              .map(TableStats::toJson)
              .collect(Collectors.joining(",\n")).indent(2).stripTrailing()
      );
    }
  }

  record TableStats(PublicKey address,
                    int numIndexed,
                    Set<PublicKey> indexed) {


    String toJson() {
      return String.format(
          """
              {
                "address": "%s",
                "numIndexed": %d,
                "indexed": ["%s"]
              }""",
          address.toBase58(), numIndexed,
          indexed.stream().map(PublicKey::toBase58).collect(Collectors.joining("\",\""))
      );
    }
  }

  private static TableStats tableStats(final Set<PublicKey> eligible, final AddressLookupTable table) {
    int numAccounts = 0;
    final var accountsInTable = HashSet.<PublicKey>newHashSet(Math.min(eligible.size(), table.numUniqueAccounts()));
    for (final var account : eligible) {
      if (table.containKey(account)) {
        ++numAccounts;
        accountsInTable.add(account);
      }
    }
    return new TableStats(table.address(), numAccounts, accountsInTable);
  }

  private static TxStats produceStats(final byte[] txBytes,
                                      final TransactionSkeleton skeleton,
                                      final PublicKey[] nonSignerAccounts,
                                      final PublicKey[] programs,
                                      final Instruction[] instructions,
                                      final AddressLookupTable[] discoveredTables) {
    final int numTablesFound = discoveredTables.length;
    final var eligible = HashSet.<PublicKey>newHashSet(nonSignerAccounts.length);
    final var indexed = numTablesFound == 0 ? null : HashSet.<PublicKey>newHashSet(nonSignerAccounts.length);
    final var programAccounts = HashSet.<PublicKey>newHashSet(programs.length);
    programAccounts.addAll(Arrays.asList(programs));
    for (final var account : nonSignerAccounts) {
      if (programAccounts.contains(account)) {
        continue;
      }
      eligible.add(account);
      if (numTablesFound > 0) {
        for (final var table : discoveredTables) {
          if (table.containKey(account)) {
            indexed.add(account);
          }
        }
      }
    }

    if (numTablesFound == 0) {
      return TxStats.noneFound(eligible, skeleton.numIndexedAccounts(), txBytes.length);
    } else {
      final var feePayer = skeleton.feePayer();
      final var instructionsList = Arrays.asList(instructions);
      final List<TableStats> tableStatsList;
      if (numTablesFound == 1) {
        final var table = discoveredTables[0];
        final var tableStats = tableStats(eligible, table);
        tableStatsList = List.of(tableStats);
        final var newTx = Transaction.createTx(feePayer, instructionsList, table);
        return TxStats.createStats(
            eligible,
            skeleton.numIndexedAccounts(),
            indexed,
            tableStatsList,
            txBytes,
            newTx.serialized()
        );
      } else {
        final var tableStats = new TableStats[numTablesFound];
        for (int i = 0; i < numTablesFound; ++i) {
          final var table = discoveredTables[i];
          tableStats[i] = tableStats(eligible, table);
        }
        tableStatsList = Arrays.asList(tableStats);

        final var tableAccountMetas = Arrays.stream(discoveredTables)
            .map(AddressLookupTable::withReverseLookup)
            .map(LookupTableAccountMeta::createMeta)
            .toArray(LookupTableAccountMeta[]::new);
        final var newTx = Transaction.createTx(feePayer, instructionsList, tableAccountMetas);
        return TxStats.createStats(
            eligible,
            skeleton.numIndexedAccounts(),
            indexed,
            tableStatsList,
            txBytes,
            newTx.serialized()
        );
      }
    }
  }

  private static final AddressLookupTable[] NO_INCLUDES = new AddressLookupTable[0];

  protected final void handle(final Request request,
                              final Response response,
                              final Callback callback,
                              final long startExchange,
                              final Tx tx) {
    handle(request, response, callback, startExchange, tx, tx.data());
  }

  protected void handle(final Request request,
                        final Response response,
                        final Callback callback,
                        final long startExchange,
                        final Tx tx,
                        final byte[] txBytes) {
    final var queryParams = queryParams(request);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    if (skeleton.isLegacy()) {
      final var accounts = skeleton.parseNonSignerPublicKeys();
      final var programs = skeleton.parseProgramAccounts();
      final long start = System.currentTimeMillis();
      final var discoveredTables = queryParams.reRank()
          ? tableService.discoverTablesWithReRank(accounts, programs)
          : tableService.discoverTables(accounts, programs);

      if (queryParams.stats()) {
        final var txStats = produceStats(
            txBytes,
            skeleton,
            accounts,
            programs,
            skeleton.parseLegacyInstructions(),
            discoveredTables
        );
        writeResponse(response, callback, startExchange, queryParams, start, discoveredTables, txStats);
      } else {
        writeResponse(response, callback, startExchange, queryParams, start, discoveredTables);
      }
    } else {
      final int txVersion = skeleton.version();
      if (txVersion == 0) {
        final var meta = tx.meta();
        final AddressLookupTable[] includeInDiscovery;
        final AccountMeta[] accounts;
        if (meta == null) {
          final var lookupTableAccounts = skeleton.lookupTableAccounts();
          final int numTableAccounts = lookupTableAccounts.length;
          final var lookupTables = HashMap.<PublicKey, AddressLookupTable>newHashMap(numTableAccounts);
          List<PublicKey> notCached = null;
          final boolean includeTables = queryParams.includeProvidedTables();
          if (includeTables) {
            for (final var key : lookupTableAccounts) {
              var lookupTable = tableService.scanForTable(key);
              if (lookupTable == null) {
                if (notCached == null) {
                  notCached = new ArrayList<>(numTableAccounts);
                }
                notCached.add(key);
              } else {
                final var cachedTable = tableCache.getTable(key);
                if (cachedTable == null) {
                  lookupTable = lookupTable.withReverseLookup();
                  tableCache.mergeTable(0, lookupTable);
                } else {
                  lookupTable = cachedTable;
                }
                lookupTables.put(lookupTable.address(), lookupTable);
              }
            }
          } else {
            for (final var key : lookupTableAccounts) {
              var lookupTable = tableCache.getTable(key);
              if (lookupTable == null) {
                lookupTable = tableService.scanForTable(key);
                if (lookupTable == null) {
                  if (notCached == null) {
                    notCached = new ArrayList<>(numTableAccounts);
                  }
                  notCached.add(key);
                  continue;
                } else {
                  lookupTable = lookupTable.withReverseLookup();
                }
                lookupTables.put(lookupTable.address(), lookupTable);
              } else {
                lookupTables.put(lookupTable.address(), lookupTable);
              }
            }
          }
          if (notCached != null) {
            if (notCached.size() == 1) {
              final var table = tableCache.getOrFetchTable(notCached.getFirst());
              lookupTables.put(table.address(), table);
              includeInDiscovery = includeTables ? new AddressLookupTable[]{table} : NO_INCLUDES;
            } else {
              final var tables = tableCache.getOrFetchTables(notCached);
              if (includeTables) {
                includeInDiscovery = new AddressLookupTable[tables.length];
                for (int i = 0; i < tables.length; ++i) {
                  final var table = tables[i].lookupTable();
                  includeInDiscovery[i] = table;
                  lookupTables.put(table.address(), table);
                }
              } else {
                includeInDiscovery = NO_INCLUDES;
                for (final var lookupTableAccountMeta : tables) {
                  final var table = lookupTableAccountMeta.lookupTable();
                  lookupTables.put(table.address(), table);
                }
              }
            }
            if (lookupTables.size() != numTableAccounts) {
              for (final var key : lookupTableAccounts) {
                if (!lookupTables.containsKey(key)) {
                  response.setStatus(400);
                  Content.Sink.write(
                      response, true, String.format(
                          """
                              {
                                "msg": "Failed to find address lookup table %s."
                              }""", key
                      ), callback
                  );
                  return;
                }
              }
            }
          } else {
            includeInDiscovery = NO_INCLUDES;
          }
          accounts = skeleton.parseAccounts(lookupTables);
        } else {
          includeInDiscovery = NO_INCLUDES;
          final var loadedAddresses = meta.loadedAddresses();
          accounts = skeleton.parseAccounts(loadedAddresses.writable(), loadedAddresses.readonly());
        }

        final var instructions = skeleton.parseInstructions(accounts);
        final long start = System.currentTimeMillis();
        final var discoveredTables = queryParams.reRank()
            ? tableService.discoverTablesWithReRank(instructions, includeInDiscovery)
            : tableService.discoverTables(instructions, includeInDiscovery);

        if (queryParams.stats()) {
          final var nonSignerAccounts = Arrays.stream(accounts, skeleton.numSignatures(), accounts.length)
              .map(AccountMeta::publicKey)
              .toArray(PublicKey[]::new);
          final var programs = Arrays.stream(instructions)
              .map(Instruction::programId)
              .map(AccountMeta::publicKey)
              .toArray(PublicKey[]::new);
          final var txStats = produceStats(
              txBytes,
              skeleton,
              nonSignerAccounts,
              programs,
              instructions,
              discoveredTables
          );
          writeResponse(response, callback, startExchange, queryParams, start, discoveredTables, txStats);
        } else {
          writeResponse(response, callback, startExchange, queryParams, start, discoveredTables);
        }
      } else {
        response.setStatus(400);
        Content.Sink.write(
            response, true, String.format(
                """
                    {
                      "msg": "Unsupported transaction version %d."
                    }""", txVersion
            ), callback
        );
      }
    }
  }

  @Override
  protected void setResponseHeaders(final Response response) {
    super.setResponseHeaders(response);
    final var responseHeaders = response.getHeaders();
    responseHeaders.put(JSON_CONTENT);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final long startExchange = System.currentTimeMillis();
    setResponseHeaders(response);
    try (final var is = Content.Source.asInputStream(request)) {
      final var body = is.readAllBytes();
      try {
        final var encoding = getEncoding(request, response, callback);
        if (encoding != null) {
          final byte[] txBytes = encoding.decode(body);
          handle(request, response, callback, startExchange, null, txBytes);
        }
      } catch (final RuntimeException ex) {
        final var bodyString = new String(body);
        logger.log(System.Logger.Level.ERROR, "Failed to process request " + bodyString, ex);
        response.setStatus(500);
        callback.failed(ex);
      }
      return true;
    } catch (final IOException ex) {
      response.setStatus(400);
      response.getHeaders().put(JSON_CONTENT);
      Content.Sink.write(
          response, true, """
              {"msg": "Failed to read request body."}""",
          callback
      );
      return true;
    }
  }
}
