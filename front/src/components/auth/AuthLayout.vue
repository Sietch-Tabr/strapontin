<script setup>
defineOptions({ name: 'AuthLayout' })
</script>

<template>
  <div class="auth-structure">
    <div class="auth-carte">
      <slot />
    </div>
  </div>
</template>

<style scoped>
  .auth-structure {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg-muted);
    padding: 1rem;
  }
  .auth-carte {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: calc(var(--radius) * 1.5);
    padding: 2rem;
    width: 100%;
    max-width: 400px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  }
</style>
