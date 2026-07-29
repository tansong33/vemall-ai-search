<template>
  <header class="app-nav">
    <div class="app-nav__inner">
      <router-link to="/" class="app-nav__logo" aria-label="AI Search 首页">
        <span class="app-nav__logo-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <circle cx="10.5" cy="10.5" r="5.5" />
            <path d="m15 15 4 4" />
            <path d="M8 10.5h5M10.5 8v5" />
          </svg>
        </span>
        <span>AI Search</span>
      </router-link>

      <nav class="app-nav__links" aria-label="主导航">
        <router-link
          to="/"
          exact
          exact-active-class="app-nav__link--active"
          class="app-nav__link"
        >
          商品搜索
        </router-link>
        <router-link
          to="/debug"
          active-class="app-nav__link--active"
          class="app-nav__link"
        >
          链路调试
        </router-link>
        <router-link
          to="/admin"
          active-class="app-nav__link--active"
          class="app-nav__link"
        >
          管理
        </router-link>
      </nav>
    </div>
  </header>
</template>

<script>
export default {
  name: 'AppNav'
}
</script>

<style lang="scss" scoped>
.app-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 1px solid #ebebeb;
  background: rgba(250, 250, 250, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);

  &__inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    max-width: $page-max-width;
    min-height: 56px;
    margin: 0 auto;
    padding: 8px 24px;
  }

  &__logo {
    display: inline-flex;
    align-items: center;
    gap: 9px;
    color: #171717;
    font-size: 14px;
    font-weight: 600;
    letter-spacing: -0.28px;
    text-decoration: none;
  }

  &__logo-mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: #171717;
    color: #fff;

    svg {
      width: 17px;
      height: 17px;
      stroke: currentColor;
      stroke-linecap: round;
      stroke-linejoin: round;
      stroke-width: 1.8;
    }
  }

  &__links {
    display: flex;
    gap: 4px;
  }

  &__link {
    padding: 7px 13px;
    border-radius: 100px;
    color: #4d4d4d;
    font-size: 14px;
    font-weight: 500;
    text-decoration: none;
    transition: background 0.15s, color 0.15s, transform 0.15s;

    &:hover {
      background: #f2f2f2;
      color: #171717;
    }

    &:active {
      transform: scale(0.97);
    }

    &--active {
      background: #171717;
      color: #fff;

      &:hover {
        background: #171717;
        color: #fff;
      }
    }
  }
}

@media (max-width: 520px) {
  .app-nav {
    &__inner {
      padding: 8px 16px;
    }

    &__logo {
      gap: 7px;
    }

    &__link {
      padding: 7px 10px;
      font-size: 13px;
    }
  }
}
</style>
