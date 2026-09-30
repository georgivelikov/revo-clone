import { NavLink } from 'react-router-dom'

const tabs = [
  { to: '/', label: 'Home', icon: '⌂' },
  { to: '/transfer', label: 'Transfer', icon: '⇄' },
  { to: '/exchange', label: 'Exchange', icon: '⇌' },
  { to: '/card', label: 'Card', icon: '▭' },
  { to: '/vaults', label: 'Vaults', icon: '▣' },
  { to: '/analytics', label: 'Analytics', icon: '◔' },
  { to: '/activity', label: 'Activity', icon: '≣' },
]

export function BottomTabBar() {
  return (
    <nav className="flex shrink-0 border-t border-border bg-surface px-2 py-2">
      {tabs.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.to === '/'}
          className={({ isActive }) =>
            `flex flex-1 flex-col items-center gap-1 rounded-control py-2 text-xs font-medium transition-colors ${
              isActive ? 'text-accent' : 'text-muted'
            }`
          }
        >
          <span aria-hidden="true" className="text-lg leading-none">
            {tab.icon}
          </span>
          {tab.label}
        </NavLink>
      ))}
    </nav>
  )
}
