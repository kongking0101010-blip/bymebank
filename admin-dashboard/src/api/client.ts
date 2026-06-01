import axios, { AxiosError, AxiosResponse } from "axios";
import { useAuthStore } from "@/store/auth";

const api = axios.create({
  // Always same-origin. nginx (see admin-dashboard/nginx.conf) reverse-proxies
  // /api/* and /auth/* to the Spring backend. We intentionally do NOT read
  // VITE_API_BASE here — a stale build-time env var on the host once baked a
  // bad absolute base into the bundle and broke login with a 405.
  baseURL: "/",
  timeout: 30_000,
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

interface ApiEnvelope<T> {
  success: boolean;
  message?: string;
  data: T;
  timestamp?: string;
}

export interface ApiError {
  code: string;
  message: string;
  status: number;
  fields?: Record<string, string>;
}

api.interceptors.response.use(
  (res) => res,
  (err: AxiosError<{ code?: string; message?: string; fields?: Record<string, string> }>) => {
    // Only kick the user out when the server explicitly says the session is dead
    // (USER_NOT_FOUND from AuthFilter, e.g. account deleted). Random 401/403 from
    // a single endpoint should NOT clear local state — it just means that one
    // call wasn't authorized.
    const code = err.response?.data?.code;
    if (code === "USER_NOT_FOUND") {
      const path = window.location.pathname;
      if (path.startsWith("/app")) {
        useAuthStore.getState().logout();
        window.location.href = "/";
      }
    }
    const wrapped: ApiError = {
      code: code ?? "NETWORK",
      message: err.response?.data?.message ?? err.message ?? "Request failed",
      status: err.response?.status ?? 0,
      fields: err.response?.data?.fields,
    };
    return Promise.reject(wrapped);
  },
);

export async function apiGet<T>(path: string): Promise<T> {
  const { data } = await api.get<ApiEnvelope<T>>(path);
  return data.data;
}
export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const { data } = await api.post<ApiEnvelope<T>>(path, body);
  return data.data;
}
export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const { data } = await api.patch<ApiEnvelope<T>>(path, body);
  return data.data;
}
export async function apiDelete(path: string, body?: unknown): Promise<void> {
  await api.delete<ApiEnvelope<null>>(path, body === undefined ? undefined : { data: body });
}
export async function apiRaw<T>(path: string, config?: Parameters<typeof api.request>[0]) {
  const r: AxiosResponse<ApiEnvelope<T>> = await api.request({ url: path, ...config });
  return r.data;
}

export default api;
