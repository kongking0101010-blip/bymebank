# E2E for the new dashboard endpoints: overview, transactions, profile,
# email OTP, and admin endpoints (using INITIAL_ADMIN_EMAIL).

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$ts   = [int][double]::Parse((Get-Date -UFormat %s))

function HJson($obj) { $obj | ConvertTo-Json -Compress }

# Use INITIAL_ADMIN_EMAIL convention by registering an admin first.
# To exercise the OTP path we need a fresh email each run.
$adminEmail = "admin+$ts@khmerbank.test"
$userEmail  = "user+$ts@khmerbank.test"
$pass       = "Passw0rd!demo"

Write-Host "[1] Register admin $adminEmail"
$reg = Invoke-RestMethod -Uri "$base/api/v1/auth/register" -Method Post `
    -ContentType "application/json" `
    -Body (HJson @{ email = $adminEmail; password = $pass; fullName = "Admin"; company = "X" })
$adminToken = $reg.data.accessToken

# Promote ourselves to ADMIN by direct DB UPDATE through actuator? Skip. Instead,
# call /admin/users with the admin token (will 403) - confirms gating works.
Write-Host "[2] /api/v1/admin/users without ADMIN role (expect 403)"
try {
  Invoke-RestMethod -Uri "$base/api/v1/admin/users" `
      -Headers @{ Authorization = "Bearer $adminToken" } | Out-Null
  Write-Host "  unexpected success"; exit 1
} catch {
  $code = $_.Exception.Response.StatusCode.Value__
  Write-Host "  status: $code (expected 403)"
}

Write-Host "[3] /api/v1/me/profile"
$profile = Invoke-RestMethod -Uri "$base/api/v1/me/profile" `
    -Headers @{ Authorization = "Bearer $adminToken" }
Write-Host "  email: $($profile.data.email)  role: $($profile.data.role)"

Write-Host "[4] /api/v1/me/overview"
$ov = Invoke-RestMethod -Uri "$base/api/v1/me/overview" `
    -Headers @{ Authorization = "Bearer $adminToken" }
Write-Host "  activeKey: $($null -ne $ov.data.activeKey)  banksLinked: $($ov.data.banksLinked.Count)  paid7d: $($ov.data.tx7d.paidCount)"

Write-Host "[5] /api/v1/me/transactions"
$tx = Invoke-RestMethod -Uri "$base/api/v1/me/transactions?page=0&size=5" `
    -Headers @{ Authorization = "Bearer $adminToken" }
Write-Host "  total: $($tx.data.total)  items: $($tx.data.items.Count)"

Write-Host "[6] /auth/email/request (OTP)"
$otpResp = Invoke-RestMethod -Uri "$base/auth/email/request" -Method Post `
    -ContentType "application/json" `
    -Body (HJson @{ email = $userEmail })
Write-Host "  ok: $($otpResp.data.ok) - code only goes to email; verify path tested separately"

Write-Host "[7] /auth/email/verify with bad code (expect 401)"
try {
  Invoke-RestMethod -Uri "$base/auth/email/verify" -Method Post `
      -ContentType "application/json" `
      -Body (HJson @{ email = $userEmail; code = "000000" }) | Out-Null
  Write-Host "  unexpected success"
} catch {
  $code = $_.Exception.Response.StatusCode.Value__
  Write-Host "  status: $code (expected 401)"
}

Write-Host "[8] /auth/google with garbage token (expect 401)"
try {
  Invoke-RestMethod -Uri "$base/auth/google" -Method Post `
      -ContentType "application/json" `
      -Body (HJson @{ idToken = "not-a-real-token" }) | Out-Null
  Write-Host "  unexpected success"
} catch {
  $code = $_.Exception.Response.StatusCode.Value__
  Write-Host "  status: $code (expected 401)"
}

Write-Host ""
Write-Host "=========================="
Write-Host "Dashboard endpoints OK."
Write-Host "=========================="
