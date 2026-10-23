// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.unit;

import static com.google.common.truth.Truth.assertThat;

import io.github.quentinbtd.gerrit.branchreview.config.RefPattern;
import io.github.quentinbtd.gerrit.branchreview.text.CommitMessage;
import io.github.quentinbtd.gerrit.branchreview.text.MessageGenerator;
import io.github.quentinbtd.gerrit.branchreview.text.MessageGenerator.Generated;
import io.github.quentinbtd.gerrit.branchreview.text.MessageGenerator.SourceCommit;
import io.github.quentinbtd.gerrit.branchreview.text.Sanitizer;
import io.github.quentinbtd.gerrit.branchreview.text.TextOwnership;
import io.github.quentinbtd.gerrit.branchreview.text.TextOwnership.Owner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TextTest {
  private static final String KEY = "I0123456789abcdef0123456789abcdef01234567";
  private static final RefPattern FEATURE = RefPattern.parse("refs/heads/feature/*");

  @Test
  public void parsesSubjectBodyAndFooters() {
    CommitMessage m =
        CommitMessage.parse(
            "Subject line\n\nBody 1\n\nBody 2  \n\nSigned-off-by: A <a@x>\nChange-Id: Iabc\n");
    assertThat(m.subject()).isEqualTo("Subject line");
    assertThat(m.body()).isEqualTo("Body 1\n\nBody 2");
    assertThat(m.footers()).hasSize(2);
    assertThat(m.footers().get(1).key()).isEqualTo("Change-Id");
    assertThat(CommitMessage.parse(m.render())).isEqualTo(m);
  }

  @Test
  public void subjectFromBranchName() {
    Generated g =
        MessageGenerator.generate(
            "refs/heads/feature/add-new_parser", FEATURE, List.of(c("a", "x"), c("b", "y")));
    assertThat(g.subject()).isEqualTo("Add new parser");
    assertThat(g.body()).contains("Squash of 2 commits from refs/heads/feature/add-new_parser");
    assertThat(g.body()).contains("* a x");
  }

  @Test
  public void subjectUsesWhatTheTrailingWildcardMatched() {
    Generated g =
        MessageGenerator.generate(
            "refs/heads/alice/feat/add-login",
            RefPattern.parse("refs/heads/*/feat/*"),
            List.of(c("a", "x"), c("b", "y")));
    assertThat(g.subject()).isEqualTo("Add login");
  }

  @Test
  public void singleCommitUsesItsMessageWithoutTrailers() {
    Generated g =
        MessageGenerator.generate(
            "refs/heads/feature/x",
            FEATURE,
            List.of(
                c(
                    "abc",
                    "Fix the bug\n\n"
                        + "Details here.\n\n"
                        + "Change-Id: Ideadbeef\n"
                        + "Signed-off-by: A <a@b>\n")));
    assertThat(g.subject()).isEqualTo("Fix the bug");
    assertThat(g.body()).isEqualTo("Details here.");
  }

  @Test
  public void mergesAreOmittedAndListIsBounded() {
    List<SourceCommit> cs = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      cs.add(c("c" + i, "commit " + i));
    }
    cs.add(new SourceCommit("m", "Merge branch master", true));
    Generated g = MessageGenerator.generate("refs/heads/feature/x", FEATURE, cs);
    assertThat(g.body()).contains("(1 merge omitted)");
    assertThat(g.body()).contains("... and 10 more");
    assertThat(g.body()).doesNotContain("Merge branch master");
  }

  @Test
  public void sanitizesControlCharactersButKeepsUnicode() {
    String rlo = String.valueOf((char) 0x202E);
    String zwj = String.valueOf((char) 0x200D);
    assertThat(
            Sanitizer.clean(
                "a" + (char) 0 + "b" + (char) 27 + "c" + rlo + "é✓👩" + zwj + "💻\r\nx"))
        .isEqualTo("abcé✓👩" + zwj + "💻\nx");
    Generated g =
        MessageGenerator.generate(
            "refs/heads/feature/über-naïve", FEATURE, List.of(c("a", "x"), c("b", "y")));
    assertThat(g.subject()).isEqualTo("Über naïve");
  }

  @Test
  public void generatorOwnsUntouchedFields() {
    TextOwnership.Result first =
        TextOwnership.next(null, TextOwnership.State.initial(), new Generated("S1", "B1"), KEY);
    assertThat(first.message()).isEqualTo("S1\n\nB1\n\nChange-Id: " + KEY + "\n");
    TextOwnership.Result second =
        TextOwnership.next(first.message(), first.state(), new Generated("S2", "B2"), KEY);
    assertThat(second.message()).isEqualTo("S2\n\nB2\n\nChange-Id: " + KEY + "\n");
    assertThat(second.state().subject()).isEqualTo(Owner.GENERATOR);
  }

  @Test
  public void editedSubjectIsPreservedWhileBodyKeepsUpdating() {
    TextOwnership.Result first =
        TextOwnership.next(null, TextOwnership.State.initial(), new Generated("S1", "B1"), KEY);
    String edited = first.message().replace("S1", "My title");
    TextOwnership.Result second =
        TextOwnership.next(edited, first.state(), new Generated("S2", "B2"), KEY);
    assertThat(second.message()).isEqualTo("My title\n\nB2\n\nChange-Id: " + KEY + "\n");
    assertThat(second.state().subject()).isEqualTo(Owner.USER);
    assertThat(second.state().body()).isEqualTo(Owner.GENERATOR);
    // Ownership is sticky even if the user types the generated text again.
    String back = second.message().replace("My title", "S2");
    TextOwnership.Result third =
        TextOwnership.next(back, second.state(), new Generated("S3", "B3"), KEY);
    assertThat(third.message()).startsWith("S2\n\nB3\n");
  }

  @Test
  public void editedBodyIsPreservedWhileSubjectKeepsUpdating() {
    TextOwnership.Result first =
        TextOwnership.next(null, TextOwnership.State.initial(), new Generated("S1", "B1"), KEY);
    String edited = first.message().replace("B1", "Hand written\ndescription");
    TextOwnership.Result second =
        TextOwnership.next(edited, first.state(), new Generated("S2", "B2"), KEY);
    assertThat(second.message())
        .isEqualTo("S2\n\nHand written\ndescription\n\nChange-Id: " + KEY + "\n");
    assertThat(second.state().body()).isEqualTo(Owner.USER);
  }

  @Test
  public void userTrailersArePreservedAndChangeIdIsForced() {
    TextOwnership.Result first =
        TextOwnership.next(null, TextOwnership.State.initial(), new Generated("S1", "B1"), KEY);
    String withTrailers =
        "S1\n\n"
            + "B1\n\n"
            + "Bug: 123\n"
            + "Change-Id: Iffffffffffffffffffffffffffffffffffffffff\n"
            + "Reviewed-on: x\n";
    TextOwnership.Result second =
        TextOwnership.next(withTrailers, first.state(), new Generated("S2", "B2"), KEY);
    assertThat(second.message())
        .isEqualTo("S2\n\nB2\n\nBug: 123\nReviewed-on: x\nChange-Id: " + KEY + "\n");
  }

  private static SourceCommit c(String id, String msg) {
    return new SourceCommit(id, msg, false);
  }
}
