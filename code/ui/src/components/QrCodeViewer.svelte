<script lang="ts">
  import QRCode from 'qrcode'

  export let content: string = ''

  let canvasElement: HTMLCanvasElement

  $: if (canvasElement && content) {
    QRCode.toCanvas(canvasElement, content, {
      width: 220,
      margin: 2,
      color: {
        dark: '#111827',
        light: '#FFFFFF'
      }
    }, (error) => {
      if (error) console.error('Failed to generate QR code:', error)
    })
  }
</script>

<div
  class="flex flex-col items-center justify-center p-6 bg-neutral-200 border border-neutral-350 rounded text-center {$$restProps.class ?? ''}"
  {...$$restProps}
>
  <div class="bg-white p-4 rounded border border-neutral-350 shadow-sm flex justify-center">
    <canvas bind:this={canvasElement}></canvas>
  </div>
</div>
