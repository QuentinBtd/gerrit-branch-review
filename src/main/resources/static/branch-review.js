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
      // A text-styled button: keyboard and screen-reader support come with the element.
      const copy = document.createElement('button');
      copy.type = 'button';
      copy.textContent = branch;
      copy.title = `${ref} (click to copy)`;
      copy.style.cssText =
        'all: unset; cursor: pointer; overflow-wrap: anywhere; outline-offset: 2px;';
      copy.addEventListener('click', () => this.copy(branch));
      value.append(copy);
      section.append(title, value);
      this.append(section);
    }

    async copy(text) {
      let message = 'Branch name copied to clipboard';
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        // No async clipboard outside secure contexts (plain-HTTP Gerrit): legacy fallback.
        const area = document.createElement('textarea');
        area.value = text;
        area.style.cssText = 'position: fixed; opacity: 0;';
        document.body.append(area);
        area.select();
        if (!document.execCommand('copy')) message = 'Could not copy the branch name';
        area.remove();
      }
      // Gerrit shows this event as its usual toast.
      this.dispatchEvent(
        new CustomEvent('show-alert', {detail: {message}, bubbles: true, composed: true}),
      );
    }
  }

  const tag = 'branch-review-source-branch';
  if (!customElements.get(tag)) customElements.define(tag, SourceBranchItem);
  plugin.registerCustomComponent('change-metadata-item', tag);
});
