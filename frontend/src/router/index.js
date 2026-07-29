import Vue from 'vue'
import VueRouter from 'vue-router'
import SearchPage from '@/views/SearchPage.vue'
import PipelineView from '@/views/PipelineView.vue'

Vue.use(VueRouter)

const routes = [
  {
    path: '/',
    name: 'Search',
    component: SearchPage,
    meta: { title: '商品搜索' }
  },
  {
    path: '/debug',
    name: 'Debug',
    component: PipelineView,
    meta: { title: '链路调试' }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import(/* webpackChunkName: "admin" */ '@/views/AdminPage.vue'),
    meta: { title: '管理' }
  },
  {
    path: '*',
    redirect: '/'
  }
]

const router = new VueRouter({
  mode: 'history',
  base: process.env.BASE_URL,
  routes,
  scrollBehavior() {
    return { x: 0, y: 0 }
  }
})

router.afterEach(to => {
  document.title = to.meta && to.meta.title
    ? `AI Search - ${to.meta.title}`
    : 'AI Search'
})

export default router
