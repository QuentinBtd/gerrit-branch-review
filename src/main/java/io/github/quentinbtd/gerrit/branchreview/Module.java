// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview;

import static com.google.gerrit.server.project.ProjectResource.PROJECT_KIND;

import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.git.validators.OnSubmitValidationListener;
import com.google.gerrit.server.git.validators.RefOperationValidationListener;
import io.github.quentinbtd.gerrit.branchreview.api.BranchStatus;
import io.github.quentinbtd.gerrit.branchreview.api.GetStatus;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.config.ProjectConfigLoader;
import io.github.quentinbtd.gerrit.branchreview.config.ServerSettings;
import io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService;
import io.github.quentinbtd.gerrit.branchreview.state.StateStore;
import io.github.quentinbtd.gerrit.branchreview.submit.MergeHandler;
import io.github.quentinbtd.gerrit.branchreview.submit.SubmitGuard;
import io.github.quentinbtd.gerrit.branchreview.sync.CommandHints;
import io.github.quentinbtd.gerrit.branchreview.sync.EditListener;
import io.github.quentinbtd.gerrit.branchreview.sync.PushHook;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncService;
import io.github.quentinbtd.gerrit.branchreview.validation.ConfigValidator;
import io.github.quentinbtd.gerrit.branchreview.validation.StateRefProtector;
import org.eclipse.jgit.transport.PostReceiveHook;

/** Server module of the branch-review plugin. */
public class Module extends LifecycleModule {
  @Override
  protected void configure() {
    // Bind every stateful singleton explicitly in this (system) injector. The plugin's SSH and
    // HTTP injectors are children that also carry copies of server bindings; a singleton left to
    // just-in-time binding can then be instantiated a second time in a child injector, with its
    // own locks and an executor that was never started.
    bind(ServerSettings.class);
    bind(Metrics.class);
    bind(ProjectConfigLoader.class);
    bind(StateStore.class);
    bind(CommandHints.class);
    bind(SyncService.class);
    bind(SyncExecutor.class);
    bind(MergeHandler.class);
    bind(RecoveryService.class);
    bind(BranchStatus.class);
    DynamicSet.bind(binder(), ReceivePackInitializer.class).to(PushHook.class);
    DynamicSet.bind(binder(), PostReceiveHook.class).to(PushHook.class);
    DynamicSet.bind(binder(), CommitValidationListener.class).to(ConfigValidator.class);
    DynamicSet.bind(binder(), RefOperationValidationListener.class).to(StateRefProtector.class);
    DynamicSet.bind(binder(), MergeValidationListener.class).to(SubmitGuard.class);
    DynamicSet.bind(binder(), OnSubmitValidationListener.class).to(SubmitGuard.class);
    DynamicSet.bind(binder(), ChangeMergedListener.class).to(MergeHandler.class);
    DynamicSet.bind(binder(), RevisionCreatedListener.class).to(EditListener.class);
    listener().to(SyncExecutor.class);
    listener().to(RecoveryService.class);
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(PROJECT_KIND, "status").to(GetStatus.class);
            post(PROJECT_KIND, "sync").to(PostSync.class);
          }
        });
  }
}
