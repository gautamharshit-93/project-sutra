import { Navigate, Route, Routes } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import LoginPage from './pages/LoginPage.jsx';
import LandingPage from './pages/LandingPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import PurchaseRequestsPage from './pages/PurchaseRequestsPage.jsx';
import PurchaseRequestDetailPage from './pages/PurchaseRequestDetailPage.jsx';
import QuotationReviewPage from './pages/QuotationReviewPage.jsx';
import EntitiesPage from './pages/EntitiesPage.jsx';
import { CustomersPage, VendorsPage } from './pages/MasterDataPages.jsx';
import UsersPage from './pages/UsersPage.jsx';
import AuditLogPage from './pages/AuditLogPage.jsx';
import AwardRegisterPage from './pages/AwardRegisterPage.jsx';
import { VendorQuotationsPage, VendorRfqDetailPage, VendorRfqsPage } from './pages/VendorPortalPages.jsx';
import { can } from './utils/permissions.js';

const STAFF = ['ADMIN', 'PROCUREMENT', 'APPROVER', 'AUDITOR'];
const RFQ_WORK = ['ADMIN', 'PROCUREMENT', 'APPROVER'];

/** Company users land on the dashboard; vendors on their portal. */
function Home() {
  const { user } = useAuth();
  return can.useVendorPortal(user) ? <Navigate to="/portal" replace /> : <DashboardPage />;
}

const staff = (element, roles = STAFF) => <RequireAuth roles={roles}>{element}</RequireAuth>;

export default function App() {
  return (
    <Routes>
      <Route path="/welcome" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth landing="/welcome"><Layout /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="purchase-requests" element={staff(<PurchaseRequestsPage />, RFQ_WORK)} />
        <Route path="purchase-requests/:id" element={staff(<PurchaseRequestDetailPage />)} />
        <Route path="quotations/:id" element={staff(<QuotationReviewPage />)} />
        <Route path="awards" element={staff(<AwardRegisterPage />, ['ADMIN', 'APPROVER', 'AUDITOR'])} />
        <Route path="entities" element={staff(<EntitiesPage />, ['ADMIN'])} />
        <Route path="customers" element={staff(<CustomersPage />, ['ADMIN'])} />
        <Route path="vendors" element={staff(<VendorsPage />, ['ADMIN', 'PROCUREMENT'])} />
        <Route path="users" element={staff(<UsersPage />, ['ADMIN'])} />
        <Route path="audit" element={staff(<AuditLogPage />, ['ADMIN', 'APPROVER', 'AUDITOR'])} />
        <Route path="portal" element={<RequireAuth roles={['VENDOR']}><VendorRfqsPage /></RequireAuth>} />
        <Route path="portal/rfqs/:id" element={<RequireAuth roles={['VENDOR']}><VendorRfqDetailPage /></RequireAuth>} />
        <Route path="portal/quotations" element={<RequireAuth roles={['VENDOR']}><VendorQuotationsPage /></RequireAuth>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
