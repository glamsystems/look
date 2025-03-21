package systems.glam.look;

import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Comparator;

record ScoredTable(int score, AddressLookupTable table) implements Comparator<ScoredTable>, Comparable<ScoredTable> {

  @Override
  public int compare(final ScoredTable o1, final ScoredTable o2) {
    return Integer.compare(o2.score, o1.score);
  }

  @Override
  public int compareTo(final ScoredTable o) {
    return Integer.compare(o.score, score);
  }
}
