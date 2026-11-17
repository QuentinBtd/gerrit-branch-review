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
      value.title = ref;
      value.textContent = ref.replace(/^refs\/heads\//, '');
      section.append(title, value);
      this.append(section);
    }
  }

  const tag = 'branch-review-source-branch';
  if (!customElements.get(tag)) customElements.define(tag, SourceBranchItem);
  plugin.registerCustomComponent('change-metadata-item', tag);
});
