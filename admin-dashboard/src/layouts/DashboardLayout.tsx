import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { motion } from "framer-motion";
import { useEffect } from "react";
import {
  Home, Sparkles, KeyRound, FlaskConical, Receipt, Settings, User,
  Search, Bell, ArrowLeft,
} from "lucide-react";
import clsx from "clsx";

import { Logo } from "@/components/Logo";
import { VantaGlobe } from "@/components/VantaGlobe";
import { useAuthStore, AuthUser } from "@/store/auth";
import { apiGet } from "@/api/client";

const NAV = [
  { to: "/app/overview",     label: "Overview",      icon: Home },
  { to: "/app/buy",          label: "Buy API Key",   icon: Sparkles },
  { to: "/app/keys",         label: "API Keys",      icon: KeyRound },
  { to: "/app/test",         label: "Test API Key",  icon: FlaskConical },
  { to: "/app/transactions", label: "Transactions",  icon: Receipt },
  { to: "/app/profile",      label: "Profile",       icon: User },
  { to: "/app/settings",     label: "Settings",      icon: Settings },
];

export default function DashboardLayout() {
  const user    = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);
  const loc     = useLocation();

  // Keep cached profile fresh.
  useEffect(() => {
    apiGet<AuthUser & { phone?: string; company?: string }>("/api/v1/me/profile")
      .then((p) => setUser({
        id: p.id, email: p.email, fullName: p.fullName, role: p.role,
        status: p.status, avatarUrl: p.avatarUrl ?? null,
      }))
      .catch(() => { /* silent — auth filter will redirect on 401 */ });
  }, [setUser]);

  return (
    <div className="min-h-screen relative overflow-x-hidden">
      {/* Vanta animated globe — fixed behind everything, same as the home hero.
          pointer-events-none so the canvas doesn't steal clicks from the sidebar. */}
      <div className="fixed inset-0 z-0 bg-[#11154d] pointer-events-none">
        <VantaGlobe className="absolute inset-0"/>
        {/* dark wash so foreground stays readable */}
        <div className="absolute inset-0 bg-gradient-to-br from-[#11154d]/55 via-[#0a0d28]/70 to-[#0a0d28]/85"/>
        <div className="absolute inset-0 dotted-grid opacity-[0.06]"/>
      </div>

      <div className="relative z-10 flex min-h-screen">
      <aside className="hidden lg:flex w-64 flex-col border-r border-white/10 bg-white/[.06] backdrop-blur-xl text-white">
        <div className="px-5 py-5 border-b border-white/10">
          <Logo dark/>
        </div>

        <nav className="flex-1 px-3 py-4 space-y-1 scrollbar-thin overflow-y-auto">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === "/app/overview"}
              className={({ isActive }) =>
                clsx(
                  "group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition",
                  isActive
                    ? "bg-white/15 text-white ring-1 ring-white/15"
                    : "text-white/65 hover:bg-white/10 hover:text-white",
                )
              }
            >
              {({ isActive }) => (
                <>
                  {isActive && (
                    <motion.span
                      layoutId="navIndicator"
                      className="absolute inset-y-1.5 left-0 w-1 rounded-r-full bg-accent-400"
                    />
                  )}
                  <item.icon size={18} className="shrink-0"/>
                  <span>{item.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="flex-1 flex flex-col min-w-0">
        <header className="sticky top-0 z-20 bg-[#0a0d28]/55 backdrop-blur-xl border-b border-white/10">
          <div className="px-4 lg:px-8 py-3 flex items-center gap-3">
            <div className="lg:hidden"><Logo mark dark/></div>

            <div className="hidden md:flex relative flex-1 max-w-md">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/40"/>
              <input
                className="w-full rounded-xl bg-white/[.06] pl-9 pr-3 py-2.5 text-sm text-white ring-1 ring-white/10 placeholder:text-white/40 focus:outline-none focus:ring-2 focus:ring-brand-400/60 focus:bg-white/[.10] transition"
                placeholder="Search transactions, keys, merchants…"
              />
              <kbd className="absolute right-2.5 top-1/2 -translate-y-1/2 hidden md:inline-flex items-center rounded-md bg-white/10 ring-1 ring-white/10 px-1.5 py-0.5 text-[10px] font-mono text-white/55">⌘K</kbd>
            </div>

            <div className="ml-auto flex items-center gap-2">
              {/* Back to home — clear, visible button (desktop) */}
              <Link
                to="/"
                title="Back to home (stay signed in)"
                className="hidden sm:inline-flex items-center gap-1.5 rounded-full bg-white/[.06] ring-1 ring-white/10 px-3 py-1.5 text-xs font-semibold text-white/85 hover:bg-white/10 hover:text-white hover:ring-white/20 transition"
              >
                <ArrowLeft size={14}/> Back to home
              </Link>
              {/* Back to home — icon only (mobile) */}
              <Link
                to="/"
                title="Back to home"
                className="sm:hidden rounded-full p-2 text-white/70 hover:text-white hover:bg-white/10 transition"
              >
                <ArrowLeft size={18}/>
              </Link>

              <button className="rounded-full p-2 relative text-white/70 hover:text-white hover:bg-white/10 transition">
                <Bell size={18}/>
                <span className="absolute top-1.5 right-1.5 h-2 w-2 rounded-full bg-rose-400 ring-2 ring-[#0a0d28]"/>
              </button>
              <Link
                to="/app/profile"
                title="Profile"
                className="hidden sm:flex items-center gap-2 rounded-full bg-white/[.06] ring-1 ring-white/10 pl-3 pr-1.5 py-1.5 hover:bg-white/10 hover:ring-white/20 transition"
              >
                <div>
                  <div className="text-xs font-semibold text-white leading-tight">{user?.fullName}</div>
                  <div className="text-[10px] text-white/55 leading-tight">{user?.email}</div>
                </div>
                {user?.avatarUrl
                  ? <img src={user.avatarUrl}
                         alt="" className="h-7 w-7 rounded-full object-cover ring-1 ring-white/10"/>
                  : <div className="h-7 w-7 rounded-full bg-gradient-to-br from-brand-500 to-brand-800 text-white grid place-items-center text-[11px] font-bold">
                      {user?.fullName?.charAt(0) ?? "U"}
                    </div>}
              </Link>
            </div>
          </div>
        </header>

        <main className="flex-1 px-4 lg:px-8 py-6">
          <motion.div
            key={loc.pathname}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18 }}
            className="rounded-2xl bg-ink-50/95 ring-1 ring-white/10 shadow-[0_24px_60px_-20px_rgba(0,0,0,.55)] backdrop-blur-sm p-5 lg:p-7 text-ink-900"
          >
            <Outlet />
          </motion.div>
        </main>
      </div>
      </div>
    </div>
  );
}
