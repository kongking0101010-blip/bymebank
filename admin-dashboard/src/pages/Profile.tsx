import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  User, Mail, Phone, Building, Shield, LogOut, Loader2, Lock, Eye, EyeOff,
} from "lucide-react";
import toast from "react-hot-toast";

import { apiGet, apiPatch, apiPost, ApiError } from "@/api/client";
import { useAuthStore } from "@/store/auth";

interface Profile {
  id: string;
  email: string;
  fullName: string;
  phone?: string | null;
  company?: string | null;
  role: string;
  status: string;
  avatarUrl?: string | null;
  emailVerified: boolean;
  createdAt: string;
  lastLoginAt?: string | null;
}

export default function ProfilePage() {
  const qc = useQueryClient();
  const logout = useAuthStore((s) => s.logout);
  const setUser = useAuthStore((s) => s.setUser);

  const q = useQuery<Profile>({
    queryKey: ["me", "profile"],
    queryFn: () => apiGet("/api/v1/me/profile"),
  });

  const [fullName, setFullName] = useState("");
  const [phone, setPhone]       = useState("");
  const [company, setCompany]   = useState("");

  useEffect(() => {
    if (q.data) {
      setFullName(q.data.fullName ?? "");
      setPhone(q.data.phone ?? "");
      setCompany(q.data.company ?? "");
    }
  }, [q.data]);

  const save = useMutation({
    mutationFn: (body: Partial<Profile>) =>
        apiPatch<Profile>("/api/v1/me/profile", body),
    onSuccess: (p) => {
      toast.success("Profile updated");
      qc.invalidateQueries({ queryKey: ["me", "profile"] });
      setUser({
        id: p.id, email: p.email, fullName: p.fullName, role: p.role,
        status: p.status, avatarUrl: p.avatarUrl,
      });
    },
    onError: (e: ApiError) => toast.error(e.message),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    save.mutate({ fullName, phone, company });
  }

  if (q.isLoading) {
    return <div className="grid place-items-center py-20"><Loader2 className="animate-spin text-ink-400"/></div>;
  }

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Profile</h1>
        <p className="text-ink-500 mt-1">Your account details.</p>
      </div>

      <form onSubmit={submit} className="card card-pad space-y-4">
        <div className="flex items-center gap-3">
          {q.data?.avatarUrl
            ? <img src={q.data.avatarUrl} alt="avatar"
                   className="h-12 w-12 rounded-full ring-2 ring-ink-100"/>
            : <div className="h-12 w-12 rounded-full bg-gradient-to-br from-brand-500 to-brand-800 text-white grid place-items-center text-lg font-bold">
                {q.data?.fullName?.charAt(0) ?? "U"}
              </div>}
          <div>
            <div className="font-bold">{q.data?.fullName}</div>
            <div className="text-sm text-ink-500">{q.data?.email}</div>
          </div>
          <span className={`ml-auto badge ${
              q.data?.role === "ADMIN" ? "bg-amber-100 text-amber-800" : "bg-ink-100 text-ink-700"}`}>
            <Shield size={10}/> {q.data?.role}
          </span>
        </div>

        <Field icon={User}  label="Full name"  value={fullName} onChange={setFullName}/>
        <Field icon={Mail}  label="Email"      value={q.data?.email ?? ""} readOnly/>
        <Field icon={Phone} label="Phone"      value={phone}    onChange={setPhone}/>
        <Field icon={Building} label="Company" value={company}  onChange={setCompany}/>

        <div className="flex items-center justify-between pt-2">
          <button type="button"
                  onClick={() => { logout(); window.location.href = "/"; }}
                  className="btn-ghost text-red-600">
            <LogOut size={14}/> Sign out
          </button>
          <button className="btn-primary" disabled={save.isPending}>
            {save.isPending ? <Loader2 size={14} className="animate-spin"/> : "Save changes"}
          </button>
        </div>
      </form>

      {/* Password panel */}
      <PasswordPanel/>
    </div>
  );
}

function PasswordPanel() {
  const [current, setCurrent] = useState("");
  const [next, setNext]       = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow]       = useState(false);

  // We don't know server-side whether a password is set without an extra
  // endpoint; just always show "current" as optional and let the server reject.
  const save = useMutation({
    mutationFn: (body: { currentPassword?: string; newPassword: string }) =>
        apiPost("/api/v1/me/password", body),
    onSuccess: () => {
      toast.success("Password updated");
      setCurrent(""); setNext(""); setConfirm("");
    },
    onError: (e: ApiError) => toast.error(e.message),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    if (next.length < 8) { toast.error("Password must be at least 8 characters"); return; }
    if (next !== confirm) { toast.error("Passwords don't match"); return; }
    save.mutate({
      currentPassword: current.trim() ? current : undefined,
      newPassword: next,
    });
  }

  return (
    <form onSubmit={submit} className="card card-pad space-y-4">
      <div className="flex items-center gap-2">
        <Lock size={16} className="text-brand-600"/>
        <h2 className="text-sm font-bold">Password</h2>
      </div>
      <p className="text-xs text-ink-500 -mt-2">
        Sets a password so you can sign in with email + password instead of OTP.
        Leave the current field empty if you've never set one.
      </p>

      <div>
        <label className="label">Current password</label>
        <input className="input"
               type={show ? "text" : "password"}
               value={current} onChange={(e) => setCurrent(e.target.value)}
               placeholder="Leave empty if first time"/>
      </div>

      <div className="grid sm:grid-cols-2 gap-4">
        <div>
          <label className="label">New password</label>
          <input className="input"
                 type={show ? "text" : "password"}
                 minLength={8} maxLength={100}
                 value={next} onChange={(e) => setNext(e.target.value)}
                 placeholder="At least 8 chars"/>
        </div>
        <div>
          <label className="label">Confirm</label>
          <input className="input"
                 type={show ? "text" : "password"}
                 minLength={8} maxLength={100}
                 value={confirm} onChange={(e) => setConfirm(e.target.value)}
                 placeholder="Repeat"/>
        </div>
      </div>

      <div className="flex items-center justify-between">
        <button type="button" onClick={() => setShow(!show)}
                className="btn-ghost text-xs">
          {show ? <><EyeOff size={12}/> Hide</> : <><Eye size={12}/> Show</>}
        </button>
        <button className="btn-primary" disabled={save.isPending || !next}>
          {save.isPending ? <Loader2 size={14} className="animate-spin"/> : "Save password"}
        </button>
      </div>
    </form>
  );
}

function Field({ icon: Icon, label, value, onChange, readOnly }: {
  icon: any; label: string; value: string;
  onChange?: (v: string) => void; readOnly?: boolean;
}) {
  return (
    <div>
      <label className="label flex items-center gap-1.5"><Icon size={12}/> {label}</label>
      <input
        className="input"
        value={value}
        readOnly={readOnly}
        onChange={readOnly ? undefined : (e) => onChange?.(e.target.value)}
      />
    </div>
  );
}
