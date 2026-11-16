// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview.state;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.hash.Hashing;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import io.github.quentinbtd.gerrit.branchreview.config.BranchReviewConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Durable plugin state stored in the project repository itself.
 *
 * <ul>
 *   <li>{@code refs/meta/branch-review/sources/<aa>/<sha256(sourceRef)>}: the current {@link
 *       Lifecycle} of a source branch, as {@code lifecycle.json}. Each update is a new commit on
 *       top of the previous one, so the ref history is an audit log.
 *   <li>{@code refs/meta/branch-review/changes/<nn>/<changeNumber>}: the {@link ChangeLink} of a
 *       Change created by the plugin.
 *   <li>{@code refs/meta/branch-review/publications/<changeNumber>/<lifecycleId>/<sourceId>}:
 *       evidence that the source commit messages up to {@code sourceId} were published on the
 *       Change; deleted when the lifecycle ends.
 * </ul>
 *
 * <p>Every write is a compare-and-swap against the ref value that was read, so concurrent writers
 * (threads, or a restarted server replaying work) cannot silently overwrite each other.
 */
@Singleton
public class StateStore {
  public static final String SOURCES = BranchReviewConfig.STATE_NAMESPACE + "sources/";
  public static final String CHANGES = BranchReviewConfig.STATE_NAMESPACE + "changes/";
  public static final String PUBLICATIONS = BranchReviewConfig.STATE_NAMESPACE + "publications/";
  private static final String LIFECYCLE_FILE = "lifecycle.json";
  private static final String LINK_FILE = "change.json";

  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

  /** Thrown when the state ref moved since it was read. */
  public static class ConcurrentUpdateException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConcurrentUpdateException(String ref) {
      super("concurrent update of " + ref);
    }
  }

  /** A value read from a state ref together with the ref value to use for compare-and-swap. */
  public record Loaded<T>(T value, ObjectId refId) {
    public boolean exists() {
      return value != null;
    }
  }

  private final Provider<PersonIdent> serverIdent;
  private final GitReferenceUpdated gitRefUpdated;

  @Inject
  StateStore(
      @GerritPersonIdent Provider<PersonIdent> serverIdent, GitReferenceUpdated gitRefUpdated) {
    this.serverIdent = serverIdent;
    this.gitRefUpdated = gitRefUpdated;
  }

  public static String lifecycleRef(String sourceRef) {
    String h = Hashing.sha256().hashString(sourceRef, UTF_8).toString();
    return SOURCES + h.substring(0, 2) + "/" + h;
  }

  public static String changeLinkRef(int changeNumber) {
    int shard = changeNumber % 100;
    return CHANGES + (shard < 10 ? "0" : "") + shard + "/" + changeNumber;
  }

  public Loaded<Lifecycle> loadLifecycle(Repository repo, String sourceRef) throws IOException {
    Loaded<Lifecycle> l = read(repo, lifecycleRef(sourceRef), LIFECYCLE_FILE, Lifecycle.class);
    if (l.exists() && !sourceRef.equals(l.value().sourceRef)) {
      throw new IOException(
          "state ref " + lifecycleRef(sourceRef) + " does not belong to " + sourceRef);
    }
    return l;
  }

  public Loaded<ChangeLink> loadLink(Repository repo, int changeNumber) throws IOException {
    return read(repo, changeLinkRef(changeNumber), LINK_FILE, ChangeLink.class);
  }

  /** All lifecycle state refs of the repository. */
  public List<Lifecycle> listLifecycles(Repository repo) throws IOException {
    List<Lifecycle> out = new ArrayList<>();
    for (Ref r : repo.getRefDatabase().getRefsByPrefix(SOURCES)) {
      Loaded<Lifecycle> l = read(repo, r.getName(), LIFECYCLE_FILE, Lifecycle.class);
      if (l.exists()) {
        out.add(l.value());
      }
    }
    return out;
  }

  public ObjectId saveLifecycle(
      Repository repo, Project.NameKey project, Lifecycle lc, ObjectId expected, String message)
      throws IOException, ConcurrentUpdateException {
    lc.updatedOn = Instant.now().toString();
    return write(
        repo,
        project,
        lifecycleRef(lc.sourceRef),
        LIFECYCLE_FILE,
        GSON.toJson(lc),
        expected,
        message);
  }

  public ObjectId saveLink(
      Repository repo,
      Project.NameKey project,
      int changeNumber,
      ChangeLink link,
      ObjectId expected)
      throws IOException, ConcurrentUpdateException {
    return write(
        repo,
        project,
        changeLinkRef(changeNumber),
        LINK_FILE,
        GSON.toJson(link),
        expected,
        "link change " + changeNumber + " to " + link.sourceRef);
  }

  public static String toJson(Object o) {
    return GSON.toJson(o);
  }

  private <T> Loaded<T> read(Repository repo, String ref, String file, Class<T> type)
      throws IOException {
    Ref r = repo.exactRef(ref);
    if (r == null || r.getObjectId() == null) {
      return new Loaded<>(null, ObjectId.zeroId());
    }
    try (RevWalk rw = new RevWalk(repo);
        ObjectReader reader = rw.getObjectReader()) {
      RevCommit c = rw.parseCommit(r.getObjectId());
      try (TreeWalk tw = TreeWalk.forPath(reader, file, c.getTree())) {
        if (tw == null) {
          throw new IOException(ref + " has no " + file);
        }
        byte[] data = reader.open(tw.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(1 << 20);
        try {
          return new Loaded<>(GSON.fromJson(new String(data, UTF_8), type), r.getObjectId().copy());
        } catch (JsonParseException e) {
          throw new IOException("corrupt state in " + ref, e);
        }
      }
    }
  }

  private ObjectId write(
      Repository repo,
      Project.NameKey project,
      String ref,
      String file,
      String json,
      ObjectId expected,
      String message)
      throws IOException, ConcurrentUpdateException {
    ObjectId newId;
    try (ObjectInserter ins = repo.newObjectInserter()) {
      ObjectId blob = ins.insert(Constants.OBJ_BLOB, json.getBytes(UTF_8));
      DirCache dc = DirCache.newInCore();
      DirCacheBuilder b = dc.builder();
      DirCacheEntry e = new DirCacheEntry(file);
      e.setFileMode(FileMode.REGULAR_FILE);
      e.setObjectId(blob);
      b.add(e);
      b.finish();
      ObjectId tree = dc.writeTree(ins);
      PersonIdent ident = serverIdent.get();
      CommitBuilder cb = new CommitBuilder();
      cb.setTreeId(tree);
      if (expected != null && !expected.equals(ObjectId.zeroId())) {
        cb.setParentId(expected);
      }
      cb.setAuthor(ident);
      cb.setCommitter(ident);
      cb.setMessage("branch-review: " + message + "\n");
      newId = ins.insert(cb);
      ins.flush();
    }
    RefUpdate ru = repo.updateRef(ref);
    ru.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
    ru.setNewObjectId(newId);
    ru.setRefLogIdent(serverIdent.get());
    ru.setRefLogMessage("branch-review: " + message, false);
    ru.setForceUpdate(false);
    RefUpdate.Result res;
    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.PLUGIN)) {
      res = ru.update();
    }
    switch (res) {
      case NEW, FAST_FORWARD -> {
        gitRefUpdated.fire(project, ru, null);
        return newId;
      }
      case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH, NOT_ATTEMPTED ->
          throw new ConcurrentUpdateException(ref);
      default -> throw new IOException("cannot update " + ref + ": " + res);
    }
  }

  public static String publicationPrefix(int changeNumber, String lifecycleId) {
    return PUBLICATIONS + changeNumber + "/" + lifecycleId + "/";
  }

  /**
   * Deletes the source message publication evidence of an ended lifecycle (merged, or closed for a
   * new one). Idempotent. Evidence left by a failure or a crash is still found and deleted by the
   * recovery sweep ({@link #deletePublicationRefs}), since the refs themselves are the record.
   */
  public void deletePublications(
      Repository repo, Project.NameKey project, int changeNumber, String lifecycleId)
      throws IOException {
    deletePublicationRefs(
        repo,
        project,
        repo.getRefDatabase().getRefsByPrefix(publicationPrefix(changeNumber, lifecycleId)));
  }

  /**
   * Deletes {@code refs} (publication evidence only) with compare-and-swap on their read values.
   *
   * @throws IOException if any ref could not be deleted; the others are deleted regardless.
   */
  public void deletePublicationRefs(Repository repo, Project.NameKey project, List<Ref> refs)
      throws IOException {
    if (refs.isEmpty()) {
      return;
    }
    BatchRefUpdate bru = repo.getRefDatabase().newBatchUpdate();
    bru.setAllowNonFastForwards(true);
    bru.setRefLogIdent(serverIdent.get());
    bru.setRefLogMessage("branch-review: lifecycle ended", false);
    // Not atomic: one stuck ref must not keep the others alive.
    bru.setAtomic(false);
    for (Ref r : refs) {
      if (!r.getName().startsWith(PUBLICATIONS)) {
        throw new IllegalArgumentException("not publication evidence: " + r.getName());
      }
      bru.addCommand(new ReceiveCommand(r.getObjectId(), ObjectId.zeroId(), r.getName()));
    }
    try (RefUpdateContext ctx = RefUpdateContext.open(RefUpdateType.PLUGIN);
        RevWalk rw = new RevWalk(repo)) {
      bru.execute(rw, NullProgressMonitor.INSTANCE);
    }
    gitRefUpdated.fire(project, bru, null);
    List<String> failed = new ArrayList<>();
    for (ReceiveCommand c : bru.getCommands()) {
      if (c.getResult() != ReceiveCommand.Result.OK) {
        failed.add(c.getRefName() + " (" + c.getResult() + ")");
      }
    }
    if (!failed.isEmpty()) {
      throw new IOException("cannot delete " + String.join(", ", failed));
    }
  }
}
