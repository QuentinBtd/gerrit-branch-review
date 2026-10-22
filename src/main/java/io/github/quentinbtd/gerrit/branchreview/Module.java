// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview;

import static com.google.gerrit.server.project.ProjectResource.PROJECT_KIND;

import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.git.validators.RefOperationValidationListener;
import io.github.quentinbtd.gerrit.branchreview.api.GetStatus;
import io.github.quentinbtd.gerrit.branchreview.api.PostSync;
import io.github.quentinbtd.gerrit.branchreview.recovery.RecoveryService;
import io.github.quentinbtd.gerrit.branchreview.submit.MergeHandler;
import io.github.quentinbtd.gerrit.branchreview.submit.SubmitGuard;
import io.github.quentinbtd.gerrit.branchreview.sync.PushHook;
import io.github.quentinbtd.gerrit.branchreview.sync.SyncExecutor;
import io.github.quentinbtd.gerrit.branchreview.validation.ConfigValidator;
import io.github.quentinbtd.gerrit.branchreview.validation.StateRefProtector;
import org.eclipse.jgit.transport.PostReceiveHook;

/** Server module of the branch-review plugin. */
public class Module extends LifecycleModule {
  @Override
  protected void configure() {
    DynamicSet.bind(binder(), ReceivePackInitializer.class).to(PushHook.class);
    DynamicSet.bind(binder(), PostReceiveHook.class).to(PushHook.class);
    DynamicSet.bind(binder(), CommitValidationListener.class).to(ConfigValidator.class);
    DynamicSet.bind(binder(), RefOperationValidationListener.class).to(StateRefProtector.class);
    DynamicSet.bind(binder(), MergeValidationListener.class).to(SubmitGuard.class);
    DynamicSet.bind(binder(), ChangeMergedListener.class).to(MergeHandler.class);
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
