<script lang="ts">
  import {Highlight} from 'svelte-highlight'
  import xml from 'svelte-highlight/languages/xml'

  import 'svelte-highlight/styles/github.css'

  export let content: string = ''

  $: formatted = formatXml(content)

  function formatXml(src: string): string {
    try {
      const doc = new DOMParser().parseFromString(src, 'application/xml')
      if (doc.querySelector('parsererror')) return src
      return serializeNode(doc.documentElement, 0)
    } catch {
      return src
    }
  }

  function serializeNode(node: Element, depth: number): string {
    const indent = '    '.repeat(depth)
    const attrs = Array.from(node.attributes).map(a => ` ${a.name}="${a.value}"`).join('')
    const elementChildren = Array.from(node.children)

    if (elementChildren.length === 0) {
      const text = node.textContent?.trim() ?? ''
      return text
        ? `${indent}<${node.tagName}${attrs}>${text}</${node.tagName}>`
        : `${indent}<${node.tagName}${attrs}/>`
    }

    const inner = elementChildren.map(n => serializeNode(n, depth + 1)).join('\n')
    return `${indent}<${node.tagName}${attrs}>\n${inner}\n${indent}</${node.tagName}>`
  }
</script>

<Highlight
  language={xml}
  code={formatted}
  class="overflow-x-auto text-sm leading-relaxed {$$restProps.class}"
/>
