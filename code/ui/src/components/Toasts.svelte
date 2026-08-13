<script lang="ts">
  import Icon from 'src/icons/Icon.svelte'
  import {hideToast, toastStore, ToastType} from 'src/stores/toasts'
  import {fly} from 'svelte/transition'

  function getToastStyle(type: ToastType) {
    if (type === ToastType.WARNING) return 'bg-warning-100 border-warning-600'
    if (type === ToastType.SUCCESS) return 'bg-success-100 border-success-600'
    if (type === ToastType.ERROR) return 'bg-danger-100 border-danger-600'
    return 'bg-primary-100 border-primary-600'
  }
</script>

<div class="fixed z-50 bottom-4 right-4 left-4 sm:left-auto flex flex-col items-end space-y-4 pointer-events-none" aria-live="assertive">
  {#each $toastStore as toast}
    <div class="toast w-full sm:w-[312px] border rounded shadow-md p-4 pointer-events-auto transition-all {getToastStyle(toast.type)}" transition:fly={{y: 50}}>
      <div class="flex justify-between items-start gap-3">
        <div class="flex-1 whitespace-pre-line">
          {#if toast.title}
            <p class="mb-1 text-base font-semibold text-neutral-850">{toast.title}</p>
          {/if}
          <p class="text-sm text-neutral-850">
            {#if toast.html}
              {@html toast.message}
            {:else}
              {toast.message}
            {/if}
          </p>
        </div>
        <button onclick={() => hideToast(toast)} class="text-neutral-700 hover:text-neutral-900 focus:outline-none p-0.5 rounded shrink-0">
          <Icon name="x"/>
        </button>
      </div>
    </div>
  {/each}
</div>