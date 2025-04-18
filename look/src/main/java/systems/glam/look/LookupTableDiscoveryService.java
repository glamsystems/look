package systems.glam.look;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.request_capacity.context.CallContext;
import software.sava.services.solana.alt.CachedAddressLookupTable;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static systems.glam.look.LookupTableDiscoveryServiceImpl.*;

public interface LookupTableDiscoveryService extends Runnable {


  static LookupTableDiscoveryService createService(final ExecutorService executorService,
                                                   final LookupTableServiceConfig serviceConfig,
                                                   final NativeProgramClient nativeProgramClient) {
    final var discoveryConfig = serviceConfig.discoveryServiceConfig();
    final var loadConfig = discoveryConfig.remoteLoadConfig();
    final var altProgram = nativeProgramClient.accounts().addressLookupTableProgram();
    final var partitions = new AtomicReferenceArray<AddressLookupTable[]>(NUM_PARTITIONS);
    final var rpcClients = serviceConfig.rpcClients();
    final var callWeights = serviceConfig.callWeights();
    final var noAuthorityCall = Call.createCourteousCall(
        rpcClients, rpcClient -> rpcClient.getProgramAccounts(
            altProgram,
            List.of(
                ACTIVE_FILTER,
                NO_AUTHORITY_FILTER
            ),
            CachedAddressLookupTable.FACTORY
        ),
        CallContext.createContext(callWeights.getProgramAccounts(), 0, false),
        "rpcClient::getProgramAccounts"
    );
    final var partitionedCallHandlers = new PartitionedLookupTableCallHandler[NUM_PARTITIONS];
    final var tableStats = TableStats.createStats(
        loadConfig.minUniqueAccountsPerTable(),
        loadConfig.minTableEfficiency()
    );
    partitionedCallHandlers[0] = new PartitionedLookupTableCallHandler(
        executorService,
        noAuthorityCall,
        tableStats,
        0,
        partitions
    );
    for (int i = 1; i < NUM_PARTITIONS; ++i) {
      final var partitionFilter = PARTITION_FILTERS[i];
      final var call = Call.createCourteousCall(
          serviceConfig.rpcClients(), rpcClient -> rpcClient.getProgramAccounts(
              altProgram,
              List.of(
                  ACTIVE_FILTER,
                  partitionFilter
              ),
              CachedAddressLookupTable.FACTORY
          ),
          CallContext.createContext(callWeights.getProgramAccounts(), 0, false),
          "rpcClient::getProgramAccounts"
      );
      partitionedCallHandlers[i] = new PartitionedLookupTableCallHandler(
          executorService,
          call,
          tableStats,
          i,
          partitions
      );
    }


    final var altCacheDirectory = discoveryConfig.cacheDirectory();
    if (discoveryConfig.clearCache()
        && altCacheDirectory != null
        && Files.exists(altCacheDirectory)) {
      for (final var file : Objects.requireNonNull(altCacheDirectory.toFile().listFiles())) {
        if (!file.isDirectory()) {
          file.delete();
        }
      }
    }

    final var queryConfig = discoveryConfig.queryConfig();
    return new LookupTableDiscoveryServiceImpl(
        executorService,
        loadConfig.maxConcurrentRequests(),
        tableStats,
        partitions,
        partitionedCallHandlers,
        altCacheDirectory,
        discoveryConfig.cacheOnly(),
        loadConfig.reloadDelay(),
        queryConfig.numPartitions(),
        queryConfig.topTablesPerPartition(),
        queryConfig.startingMinScore()
    );
  }

  static Set<PublicKey> distinctAccounts(final Instruction[] instructions) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(Transaction.MAX_ACCOUNTS);
    for (final var ix : instructions) {
      for (final var account : ix.accounts()) {
        if (!account.signer() && !account.invoked()) {
          distinctAccounts.add(account.publicKey());
        }
      }
    }
    for (final var ix : instructions) {
      distinctAccounts.remove(ix.programId().publicKey());
    }
    return distinctAccounts;
  }

  static Set<PublicKey> distinctAccounts(final Transaction transaction) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(Transaction.MAX_ACCOUNTS);
    final var instructions = transaction.instructions();
    for (final var ix : instructions) {
      for (final var account : ix.accounts()) {
        if (!account.signer() && !account.invoked()) {
          distinctAccounts.add(account.publicKey());
        }
      }
    }
    return distinctAccounts;
  }

  static Set<PublicKey> distinctAccounts(final PublicKey[] accounts, final PublicKey[] programs) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(accounts.length);
    //noinspection ManualArrayToCollectionCopy
    for (final var account : accounts) {
      //noinspection UseBulkOperation
      distinctAccounts.add(account);
    }
    for (final var program : programs) {
      distinctAccounts.remove(program);
    }
    return distinctAccounts;
  }

  CompletableFuture<Void> initializedFuture();

  CompletableFuture<Void> remoteLoadFuture();

  AddressLookupTable[] discoverTables(final Set<PublicKey> accounts);

  AddressLookupTable[] discoverTables(final Transaction transaction);

  default AddressLookupTable[] discoverTables(final Instruction[] instructions) {
    return discoverTables(distinctAccounts(instructions));
  }

  default AddressLookupTable[] discoverTables(final PublicKey[] accounts, final PublicKey[] programs) {
    return discoverTables(distinctAccounts(accounts, programs));
  }

  AddressLookupTable[] discoverTablesWithReRank(final Set<PublicKey> distinctAccounts);

  default AddressLookupTable[] discoverTablesWithReRank(final Instruction[] instructions) {
    return discoverTablesWithReRank(distinctAccounts(instructions));
  }

  default AddressLookupTable[] discoverTablesWithReRank(final PublicKey[] accounts, final PublicKey[] programs) {
    return discoverTablesWithReRank(distinctAccounts(accounts, programs));
  }

  AddressLookupTable[] discoverTablesWithReRank(final Set<PublicKey> distinctAccounts,
                                                final AddressLookupTable[] include);

  default AddressLookupTable[] discoverTablesWithReRank(final Instruction[] instructions,
                                                        final AddressLookupTable[] include) {
    return discoverTablesWithReRank(distinctAccounts(instructions), include);
  }

  default AddressLookupTable[] discoverTablesWithReRank(final PublicKey[] accounts,
                                                        final PublicKey[] programs,
                                                        final AddressLookupTable[] include) {
    return discoverTablesWithReRank(distinctAccounts(accounts, programs), include);
  }

  AddressLookupTable[] discoverTables(final Set<PublicKey> distinctAccounts, final AddressLookupTable[] include);

  default AddressLookupTable[] discoverTables(final Instruction[] instructions, final AddressLookupTable[] include) {
    return discoverTables(distinctAccounts(instructions), include);
  }

  default AddressLookupTable[] discoverTables(final PublicKey[] accounts,
                                              final PublicKey[] programs,
                                              final AddressLookupTable[] include) {
    return discoverTables(distinctAccounts(accounts, programs), include);
  }

  AddressLookupTable scanForTable(final PublicKey publicKey);

  CompletableFuture<Void> initialized();

  boolean loadCache();
}
