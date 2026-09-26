import { Navigate, Route, Routes } from 'react-router-dom'
import { RequireAuth } from './auth/RequireAuth'
import { AppShell } from './layout/AppShell'
import { ActivityDetailScreen } from './screens/ActivityDetailScreen'
import { ActivityScreen } from './screens/ActivityScreen'
import { HomeScreen } from './screens/HomeScreen'
import { TransferScreen } from './screens/TransferScreen'

export default function App() {
  return (
    <RequireAuth>
      <AppShell>
        <Routes>
          <Route path="/" element={<HomeScreen />} />
          <Route path="/transfer" element={<TransferScreen />} />
          <Route path="/activity" element={<ActivityScreen />} />
          <Route path="/activity/:paymentId" element={<ActivityDetailScreen />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell>
    </RequireAuth>
  )
}
