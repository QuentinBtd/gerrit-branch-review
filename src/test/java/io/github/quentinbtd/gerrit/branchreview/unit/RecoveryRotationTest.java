// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;

import io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecoveryRotationTest {
  private static final List<String> KEYS = List.of("a", "b", "c", "d");

  /** Admits {@code capacity} items per pass and records the admitted keys. */
  private static String pass(String resume, int capacity, List<String> admitted) {
    int[] left = {capacity};
    return RecoveryService.admitInRotation(
        KEYS,
        resume,
        k -> {
          if (left[0] == 0) {
            return false;
          }
          left[0]--;
          admitted.add(k);
          return true;
        });
  }

  @Test
  public void everyItemIsReachedWithOneSlotPerPass() {
    List<String> admitted = new ArrayList<>();
    String resume = null;
    for (int i = 0; i < 4; i++) {
      resume = pass(resume, 1, admitted);
    }
    assertThat(admitted).containsExactly("a", "b", "c", "d").inOrder();
    // The rotation then wraps around.
    pass(resume, 1, admitted);
    assertThat(admitted.get(4)).isEqualTo("a");
  }

  @Test
  public void resumeIsClearedWhenEverythingFits() {
    List<String> admitted = new ArrayList<>();
    assertThat(pass("b", 10, admitted)).isNull();
    assertThat(admitted).containsExactly("c", "d", "a", "b").inOrder();
  }

  @Test
  public void nothingAdmittedKeepsThePosition() {
    assertThat(pass("b", 0, new ArrayList<>())).isEqualTo("b");
  }

  @Test
  public void vanishedResumeKeyStartsAtTheNextKey() {
    List<String> admitted = new ArrayList<>();
    pass("bb", 1, admitted);
    assertThat(admitted).containsExactly("c");
  }
}
