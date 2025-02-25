package systems.glam.look;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.services.core.remote.call.Call;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceArray;

final class PartitionedLookupTableCallHandler extends LookupTableCallHandler {

  private final int partition;
  private final AtomicReferenceArray<AddressLookupTable[]> partitions;

  PartitionedLookupTableCallHandler(final ExecutorService executorService,
                                    final Call<List<AccountInfo<AddressLookupTable>>> call,
                                    final TableStats tableStats,
                                    final int partition,
                                    final AtomicReferenceArray<AddressLookupTable[]> partitions) {
    super(executorService, call, tableStats);
    this.partition = partition;
    this.partitions = partitions;
  }


  @Override
  public AddressLookupTable[] apply(final List<AccountInfo<AddressLookupTable>> accountInfos) {
    final var tables = super.apply(accountInfos);
    partitions.set(partition, tables);
    return tables;
  }
}
