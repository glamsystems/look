package systems.glam.look.http;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.TransactionSkeleton;

import java.util.*;
import java.util.stream.Collectors;

public record TransactionAccounts(String id,
                                  TransactionSkeleton skeleton,
                                  AccountMeta[] accounts,
                                  Set<PublicKey> indexedAccounts) {

  public static final class Scores {

    public int eligibleScore;
    public int notIndexedScore;

    private Scores() {
      this.eligibleScore = 1;
    }
  }

  public Set<PublicKey> indexedAccountKeys() {
    final var includedAccounts = Arrays.stream(skeleton.parseAccounts())
        .map(AccountMeta::publicKey)
        .collect(Collectors.toSet());
    return Arrays.stream(accounts).<PublicKey>mapMulti((account, downstream) -> {
      if (!includedAccounts.contains(account.publicKey())) {
        downstream.accept(account.publicKey());
      }
    }).collect(Collectors.toSet());
  }

  public Set<PublicKey> aggregateMustBeIncludedKeys(final Set<PublicKey> sharedMustBeIncludedKeys) {
    final var invokedAccounts = skeleton.parseProgramAccounts();
    final var mustBeIncludedKeys = HashSet.<PublicKey>newHashSet(skeleton.numSigners() + invokedAccounts.length);
    Collections.addAll(mustBeIncludedKeys, invokedAccounts);
    Collections.addAll(sharedMustBeIncludedKeys, invokedAccounts);
    for (final var account : accounts) {
      if (account.signer()) {
        final var key = account.publicKey();
        mustBeIncludedKeys.add(key);
        sharedMustBeIncludedKeys.add(key);
      } else {
        break;
      }
    }
    return tableEligibleAccounts(mustBeIncludedKeys);
  }

  public void tableEligibleAccounts(final Set<PublicKey> mustBeIncludedKeys,
                                    final Map<PublicKey, Scores> eligibleCounts) {
    final int numAccounts = accounts.length;
    int a = skeleton.numSigners();
    for (; a < numAccounts; a++) {
      final var account = accounts[a].publicKey();
      if (!mustBeIncludedKeys.contains(account)) {
        var score = eligibleCounts.get(account);
        if (score == null) {
          score = new Scores();
          eligibleCounts.put(account, score);
        } else {
          score.eligibleScore++;
        }
        if (!indexedAccounts.contains(account)) {
          score.notIndexedScore++;
        }
      }
    }
  }

  public Set<PublicKey> tableEligibleAccounts(final Set<PublicKey> mustBeIncludedKeys) {
    final int numAccounts = accounts.length;
    int a = skeleton.numSigners();
    final var eligibleAccounts = HashSet.<PublicKey>newHashSet(numAccounts - a);
    for (; a < numAccounts; a++) {
      final var account = accounts[a].publicKey();
      if (!mustBeIncludedKeys.contains(account)) {
        eligibleAccounts.add(account);
      }
    }
    return eligibleAccounts;
  }

  public Set<PublicKey> tableEligibleAccounts() {
    final var invokedAccounts = skeleton.parseProgramAccounts();
    final var mustBeIncludedKeys = HashSet.<PublicKey>newHashSet(skeleton.numSigners() + invokedAccounts.length);
    aggregateMustBeIncludedKeys(mustBeIncludedKeys);
    return tableEligibleAccounts(mustBeIncludedKeys);
  }
}
