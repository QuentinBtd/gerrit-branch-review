// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

// Shows the source branch of a branch-review Change in the change-page metadata block. The value
// comes from ChangeInfo.plugins, filled by SourceBranchAttribute; other Changes show nothing.
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
      // Keep name and button on one line, as gr-commit-info does for the SHA.
      const line = document.createElement('span');
      line.style.cssText = 'display: inline-flex; align-items: center; gap: var(--spacing-s, 4px);';
      line.append(label, copy);
      value.append(line);
      section.append(title, value);
      this.append(section);
    }
  }

  const tag = 'branch-review-source-branch';
  if (!customElements.get(tag)) customElements.define(tag, SourceBranchItem);
  plugin.registerCustomComponent('change-metadata-item', tag);
});
