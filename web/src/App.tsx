import { Navigate, Route, Routes } from 'react-router-dom'
import { RequireAuth } from './auth/RequireAuth'
import { DevDrawer } from './dev/DevDrawer'
import { usePaymentPolling } from './hooks/usePaymentPolling'
import { AppShell } from './layout/AppShell'
import { ActivityDetailScreen } from './screens/ActivityDetailScreen'
import { ActivityScreen } from './screens/ActivityScreen'
import { AnalyticsScreen } from './screens/AnalyticsScreen'
import { CardScreen } from './screens/CardScreen'
import { ExchangeScreen } from './screens/ExchangeScreen'
import { HomeScreen } from './screens/HomeScreen'
import { TransferScreen } from './screens/TransferScreen'
import { VaultsScreen } from './screens/VaultsScreen'
import { useNotificationSocket } from './ws/useNotificationSocket'

// Mounted once, for the lifetime of an authenticated session, regardless of which screen is
// showing - a transfer's reconciliation (socket settle, or the 5s polling fallback) must not
// stop just because the user navigated away from Transfer or Activity.
function usePaymentReconciliation() {
  useNotificationSocket()
  usePaymentPolling()
}

export default function App() {
  usePaymentReconciliation()

  return (
    <RequireAuth>
      <AppShell>
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/transfer" element={<TransferScreen />} />
          <Route path="/exchange" element={<ExchangeScreen />} />
          <Route path="/card" element={<CardScreen />} />
          <Route path="/vaults" element={<VaultsScreen />} />
          <Route path="/analytics" element={<AnalyticsScreen />} />
          <Route path="/activity" element={<ActivityScreen />} />
          <Route path="/activity/:paymentId" element={<ActivityDetailScreen />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell>
      <DevDrawer />
    </RequireAuth>
  )
}
