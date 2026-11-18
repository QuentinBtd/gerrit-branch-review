// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

// Shows the source branch of a branch-review Change in the change-page metadata block (from
// ChangeInfo.plugins, filled by SourceBranchAttribute; other Changes show nothing), and runs the
// "Delete source branch" change action that DeleteSourceBranch offers on closed Changes.
Gerrit.install((plugin) => {
  const name = plugin.getPluginName();

  class SourceBranchItem extends HTMLElement {
    set change(c) {
      this._change = c;
      this.render();
    }

    get change() {
      return this._change;
    }

    render() {
      const info = (this._change?.plugins || []).find((p) => p.name === name);
      const ref = info?.source_branch;
      this.replaceChildren();
      if (!ref) return;
      // Light DOM with the classes of gr-change-metadata, so the row inherits its styles.
      const section = document.createElement('section');
      const title = document.createElement('span');
      title.className = 'title';
      title.textContent = 'Source branch';
      const value = document.createElement('span');
      value.className = 'value';
      const branch = ref.replace(/^refs\/heads\//, '');
      // Link to the repository browser (e.g. gitiles) while the branch exists, else plain text.
      const url = info.source_branch_url;
      const label = document.createElement(url ? 'a' : 'span');
      if (url) label.href = url;
      label.title = ref;
      label.textContent = branch;
      // Gerrit's own copy button, as next to the commit SHA.
      const copy = document.createElement('gr-copy-clipboard');
      copy.text = branch;
      copy.hideInput = true;
      copy.hasTooltip = true;
      copy.buttonTitle = 'Copy branch name to clipboard';
      copy.copyTargetName = 'Branch name';
      // Gerrit's button needs the async clipboard API, which plain-HTTP pages do not have: there,
      // take the click before it reaches the inner button and copy the legacy way.
      copy.addEventListener(
        'click',
        (e) => {
          if (navigator.clipboard) return;
          e.stopPropagation();
          legacyCopy(branch, copy);
        },
        true,
      );
      // Keep name and button on one line, as gr-commit-info does for the SHA.
      const line = document.createElement('span');
      line.style.cssText = 'display: inline-flex; align-items: center; gap: var(--spacing-s, 4px);';
      line.append(label, copy);
      value.append(line);
      section.append(title, value);
      this.append(section);
    }
  }

  function legacyCopy(text, source) {
    let focused = document.activeElement;
    while (focused?.shadowRoot?.activeElement) focused = focused.shadowRoot.activeElement;
    const area = document.createElement('textarea');
    area.value = text;
    area.style.cssText = 'position: fixed; opacity: 0;';
    document.body.append(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    focused?.focus(); // select() moved focus to the textarea
    toast(source, ok ? 'Branch name copied to clipboard' : 'Could not copy the branch name');
  }

  function toast(source, message) {
    // Gerrit shows this event as its usual toast.
    source.dispatchEvent(
      new CustomEvent('show-alert', {detail: {message}, bubbles: true, composed: true}),
    );
  }

  // Gerrit renders plugin change actions but leaves running them to the plugin.
  const deleteAction = `${name}~delete-source-branch`;
  let shownChange;
  async function deleteSourceBranch(e) {
    const change = shownChange;
    const node = e.detail?.node ?? document.body;
    const info = (change?.plugins || []).find((p) => p.name === name);
    const branch = (info?.source_branch ?? 'the source branch').replace(/^refs\/heads\//, '');
    if (!window.confirm(`Delete the branch ${branch}? This cannot be undone.`)) return;
    try {
      await plugin
        .restApi()
        .post(
          `/changes/${encodeURIComponent(change.project)}~${change._number}/${deleteAction}`,
          {},
        );
      toast(node, `Deleted source branch ${branch}`);
      // Reload the Change: the action and the branch link go away.
      node.dispatchEvent(new CustomEvent('reload', {detail: {}, bubbles: true, composed: true}));
    } catch (err) {
      toast(node, `Could not delete ${branch}: ${err?.message ?? err}`);
    }
  }
  plugin.on('showchange', (change) => {
    shownChange = change;
    // The same function object: re-adding it on every Change view is a no-op.
    plugin.changeActions().addTapListener(deleteAction, deleteSourceBranch);
  });

  const tag = 'branch-review-source-branch';
  if (!customElements.get(tag)) customElements.define(tag, SourceBranchItem);
  plugin.registerCustomComponent('change-metadata-item', tag);
});
