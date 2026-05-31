import { Navigate, Route, Routes } from "react-router-dom";
import { useEffect } from "react";

import LoginPage from "@/pages/Login";
import LandingPage from "@/pages/Landing";
import DashboardLayout from "@/layouts/DashboardLayout";
import OverviewPage from "@/pages/Overview";
import BuyKeyPage from "@/pages/BuyKey";
import KeysPage from "@/pages/Keys";
import TestKeyPage from "@/pages/TestKey";
import TransactionsPage from "@/pages/Transactions";
import ProfilePage from "@/pages/Profile";
import SettingsPage from "@/pages/Settings";

import { useAuthStore } from "@/store/auth";
import { useBrandingStore } from "@/store/branding";
import { useNoInspect } from "@/hooks/useNoInspect";

function Protected({ children }: { children: JSX.Element }) {
  const token = useAuthStore((s) => s.token);
  return token ? children : <Navigate to="/login" replace />;
}

export default function App() {
  // Block F12 / Ctrl+Shift+I / right-click → Inspect in production
  // builds. No-op in dev so we don't lock ourselves out while iterating.
  useNoInspect();

  // Bootstrap branding once at app mount so logos appear everywhere.
  const loadBranding    = useBrandingStore((s) => s.load);
  const refreshBranding = useBrandingStore((s) => s.refreshPublic);

  useEffect(() => {
    void loadBranding();

    // Pull fresh logos whenever the tab regains focus. Covers the
    // "I just changed my brand logo on the Telegram bot, switched
    // back to the dashboard, but the old one is still cached" gap
    // — both Spring (60s) and the bridge (60s) caches are bypassed
    // via ?refresh=true on the public branding endpoint, so the new
    // logo lands within ~1 second of you returning to this tab.
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        void refreshBranding();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("focus", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("focus", onVisibility);
    };
  }, [loadBranding, refreshBranding]);

  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      {/* /register kept as a redirect for old links so users land on login. */}
      <Route path="/register" element={<Navigate to="/login" replace />} />

      <Route
        path="/app"
        element={
          <Protected>
            <DashboardLayout />
          </Protected>
        }
      >
        <Route index               element={<Navigate to="overview" replace />}/>
        <Route path="overview"     element={<OverviewPage />} />
        <Route path="keys"         element={<KeysPage />} />
        <Route path="buy"          element={<BuyKeyPage />} />
        {/* Legacy alias — old links still work */}
        <Route path="keys/buy"     element={<Navigate to="/app/buy" replace />} />
        <Route path="test"         element={<TestKeyPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="profile"      element={<ProfilePage />} />
        <Route path="settings"     element={<SettingsPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
