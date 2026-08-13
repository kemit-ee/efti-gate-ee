<script lang="ts">
  export let icon = ''
  export let size: 'sm'|''|'lg' = ''
  export let label = ''
  export let type: 'button'|'submit' = 'button'

  $: hasLabel = label || $$slots.default
</script>

<button
  {type}
  {...$$restProps}
  class="btn inline-flex items-center justify-center font-medium rounded-full border transition-colors duration-150 focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:pointer-events-none min-w-[40px] max-w-full truncate {size} {$$props.class ?? 'secondary'}"
  class:icon-only={icon && !hasLabel}
>
  {#if hasLabel}
    <span class="truncate"><slot>{label}</slot></span>
  {/if}
</button>

<style>
  .btn {
    @apply h-10 px-4 py-2 text-base gap-2;
  }
  .btn.sm {
    @apply h-8 px-3 py-1.5 text-sm gap-1.5;
  }
  .btn.icon-only {
    @apply p-2;
  }
  .btn.icon-only.sm {
    @apply p-1.5;
  }
  .btn.primary {
    @apply bg-primary-600 text-white border-transparent hover:bg-primary-700 active:bg-primary-800 focus:ring-primary-600;
  }
  .btn.secondary {
    @apply bg-transparent text-primary-600 border-primary-600 hover:bg-primary-100 hover:text-primary-700 active:bg-primary-200 focus:ring-primary-600;
  }
  .btn.neutral {
    @apply bg-transparent text-secondary-800 border-transparent hover:bg-neutral-300 active:bg-neutral-400 focus:ring-secondary-800 !px-0;
  }
  .btn.success {
    @apply bg-success-600 text-white border-transparent hover:bg-success-700 active:bg-success-800 focus:ring-success-600;
  }
  .btn.danger {
    @apply bg-danger-600 text-white border-transparent hover:bg-danger-700 active:bg-danger-800 focus:ring-danger-600;
  }
  .btn.danger-neutral {
    @apply bg-transparent text-danger-600 border-transparent hover:bg-danger-100 active:bg-danger-200 focus:ring-danger-600 !px-0;
  }
  .btn.primary-inverted {
    @apply bg-white text-primary-600 border-transparent hover:bg-neutral-200 active:bg-neutral-300 focus:ring-white;
  }
  .btn.secondary-inverted {
    @apply bg-transparent text-white border-white hover:bg-white/10 active:bg-white/20 focus:ring-white;
  }
  .btn.neutral-inverted {
    @apply bg-transparent text-white border-transparent hover:bg-white/10 active:bg-white/20 focus:ring-white !px-0;
  }
</style>