import { Navigate, Route, Routes } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import LoginPage from './pages/LoginPage.jsx';
import VendorSignupPage from './pages/VendorSignupPage.jsx';
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
import { VendorProfilePage, VendorQuotationsPage, VendorRfqDetailPage, VendorRfqsPage } from './pages/VendorPortalPages.jsx';
import { can } from './utils/permissions.js';

const STAFF = ['ADMIN', 'PROCUREMENT', 'APPROVER'];
const BUYERS = ['ADMIN', 'PROCUREMENT'];
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
      <Route path="/register" element={<VendorSignupPage />} />
      <Route element={<RequireAuth landing="/welcome"><Layout /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="purchase-requests" element={staff(<PurchaseRequestsPage />, RFQ_WORK)} />
        <Route path="purchase-requests/:id" element={staff(<PurchaseRequestDetailPage />)} />
        <Route path="quotations/:id" element={staff(<QuotationReviewPage />)} />
        <Route path="awards" element={staff(<AwardRegisterPage />)} />
        <Route path="entities" element={staff(<EntitiesPage />, BUYERS)} />
        <Route path="customers" element={staff(<CustomersPage />, BUYERS)} />
        <Route path="vendors" element={staff(<VendorsPage />, BUYERS)} />
        <Route path="users" element={staff(<UsersPage />, ['ADMIN'])} />
        <Route path="audit" element={staff(<AuditLogPage />, ['ADMIN', 'APPROVER'])} />
        <Route path="portal" element={<RequireAuth roles={['VENDOR']}><VendorRfqsPage /></RequireAuth>} />
        <Route path="portal/rfqs/:id" element={<RequireAuth roles={['VENDOR']}><VendorRfqDetailPage /></RequireAuth>} />
        <Route path="portal/quotations" element={<RequireAuth roles={['VENDOR']}><VendorQuotationsPage /></RequireAuth>} />
        <Route path="portal/profile" element={<RequireAuth roles={['VENDOR']}><VendorProfilePage /></RequireAuth>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
