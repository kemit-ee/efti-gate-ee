<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import QrScanner from 'qr-scanner';

  export let onScan: (result: string) => void;
  export let onClose: () => void;

  let videoElement: HTMLVideoElement;
  let qrScannerInstance: QrScanner | null = null;

  onMount(() => {
    if (!videoElement) return;

    qrScannerInstance = new QrScanner(
      videoElement,
      (result) => {
        const text = typeof result === 'object' ? result.data : result;
        onScan(text);
      },
      {
        preferredCamera: 'environment',
        highlightScanRegion: true,
        highlightCodeOutline: true,
      }
    );

    qrScannerInstance.start().catch((err) => {
      console.error('Camera initialization failed:', err);
      alert('Camera access denied or unavailable.');
      onClose();
    });
  });

  onDestroy(() => {
    if (qrScannerInstance) {
      qrScannerInstance.stop();
      qrScannerInstance.destroy();
      qrScannerInstance = null;
    }
  });
</script>

<video bind:this={videoElement} class="w-full max-w-sm aspect-square object-cover rounded mx-auto"></video>
