// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import io.github.quentinbtd.gerrit.branchreview.text.SourceCommitMessages;
import io.github.quentinbtd.gerrit.branchreview.text.SourceCommitMessages.Commit;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

public class SourceCommitMessagesTest {
  @Test
  public void retainsFullBodyAndTrailersButKeepsUntrustedTextInert() {
    String rendered =
        SourceCommitMessages.render(
            List.of(
                new Commit(
                    "a".repeat(40),
                    "Résumé 👩‍💻\r\n\r\n"
                        + "Full\u0000 body\u202E\r\n\r\n"
                        + "Bug: 123\r\n"
                        + "<GERRIT_ACCOUNT_1>")));
    assertThat(rendered).contains("Résumé 👩‍💻\n\nFull body\n\nBug: 123");
    assertThat(rendered).doesNotContain("<GERRIT_ACCOUNT_1>");
    assertThat(rendered).contains("< GERRIT_ACCOUNT_1>");
  }

  @Test
  public void boundsLargeBodiesAndGroupsWithoutSplittingUnicode() {
    String rendered =
        SourceCommitMessages.render(
            IntStream.range(0, 80)
                .mapToObj(
                    i -> new Commit(String.format("%040x", i), "subject\n\n" + "😀".repeat(9000)))
                .toList());
    assertThat(rendered.length()).isAtMost(SourceCommitMessages.MAX_MESSAGE_CHARS);
    assertThat(rendered).contains("[commit message truncated]");
    assertThat(rendered).contains("additional commit messages omitted]");
    assertThat(rendered.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isFalse();
    assertThat(SourceCommitMessages.render(List.of())).isEmpty();
    var small =
        IntStream.range(0, 51).mapToObj(i -> new Commit(String.format("%040x", i), "s")).toList();
    assertThat(SourceCommitMessages.render(small)).contains("[truncated: 1 additional");
  }

  @Test
  public void fullWatermarksAreScopedToLifecycleAndIgnoreMalformedTags() {
    var source = ObjectId.fromString("a".repeat(40));
    String tag = SourceCommitMessages.tag("life", source);
    assertThat(
            SourceCommitMessages.publishedHeads(
                "life",
                java.util.Arrays.asList(
                    tag,
                    tag,
                    null,
                    SourceCommitMessages.tag("other", source),
                    tag + "x",
                    "manual")))
        .containsExactly(source);
  }

  @Test
  public void selectsOnlyFreshReachableNonMergesInTopologicalOrder() throws Exception {
    try (var repo = new InMemoryRepository(new DfsRepositoryDescription("selection"));
        var tr = new TestRepository<>(repo)) {
      var base = tr.commit().message("base").create();
      var a = tr.commit().parent(base).message("A\n\nfull A").create();
      var b = tr.commit().parent(a).message("B\n\nfull B").create();
      var side = tr.commit().parent(a).message("side").create();
      var merge = tr.commit().parent(b).parent(side).message("merge ignored").create();
      var initial = SourceCommitMessages.select(repo, merge, base, List.of(), 20);
      assertThat(initial.stream().map(Commit::id).toList())
          .containsExactly(a.name(), b.name(), side.name());
      assertThat(initial.get(0).id()).isEqualTo(a.name());
      var c = tr.commit().parent(merge).message("C\n\nfull C").create();
      assertThat(SourceCommitMessages.select(repo, c, base, List.of(merge), 20))
          .containsExactly(new Commit(c.name(), "C\n\nfull C"));
      assertThat(SourceCommitMessages.select(repo, c, base, List.of(c), 20)).isEmpty();
      assertThrows(
          IOException.class, () -> SourceCommitMessages.select(repo, c, base, List.of(), 2));
      var rewritten = tr.commit().parent(a).message("B rewritten").create();
      assertThat(SourceCommitMessages.select(repo, rewritten, base, List.of(c), 20))
          .containsExactly(new Commit(rewritten.name(), "B rewritten"));
      assertThat(SourceCommitMessages.select(repo, b, base, List.of(c, rewritten), 20)).isEmpty();
      assertThrows(
          IOException.class,
          () ->
              SourceCommitMessages.select(
                  repo, c, base, List.of(ObjectId.fromString("f".repeat(40))), 20));
    }
  }
}
