# End-to-end smoke test: Spring → bot-bridge → live apicheckpayment bot.
# Requires: Spring on :8080, bot-bridge on :8090.

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$ts   = [int][double]::Parse((Get-Date -UFormat %s))
$email = "e2e+$ts@khmerbank.test"
$pass  = "Passw0rd!demo"

function HJson($obj) { $obj | ConvertTo-Json -Compress }

Write-Host "`n[1/5] Register user $email"
$reg = Invoke-RestMethod -Uri "$base/api/v1/auth/register" -Method Post `
    -ContentType "application/json" `
    -Body (HJson @{
        email    = $email
        password = $pass
        fullName = "E2E Test"
        company  = "E2E Co"
    })
$token = $reg.data.accessToken
if (-not $token) { Write-Host "REG FAILED:`n$($reg | ConvertTo-Json)"; exit 1 }
Write-Host "  token: $($token.Substring(0,16))..."
$auth = @{ Authorization = "Bearer $token" }

Write-Host "`n[2/5] Link Bakong merchant"
# /api/v1/merchants/upload accepts multipart form-data.
$tmp = New-TemporaryFile
$boundary = "----webform" + ($ts)
$lf = "`r`n"
$body = ""
$fields = @{
    bankType     = "BAKONG"
    merchantId   = "e2e_demo_$ts@aclb"
    phone        = "0123456789"
    merchantName = "E2E Demo $ts"
}
foreach ($k in $fields.Keys) {
    $body += "--$boundary$lf"
    $body += "Content-Disposition: form-data; name=`"$k`"$lf$lf"
    $body += "$($fields[$k])$lf"
}
$body += "--$boundary--$lf"
[System.IO.File]::WriteAllText($tmp.FullName, $body)
$linkResp = curl.exe -s -X POST "$base/api/v1/merchants/upload" `
    -H "Authorization: Bearer $token" `
    -H "Content-Type: multipart/form-data; boundary=$boundary" `
    --data-binary "@$($tmp.FullName)"
Remove-Item $tmp.FullName
Write-Host "  resp: $linkResp"
if ($linkResp -notmatch '"success":\s*true') { Write-Host "LINK FAILED"; exit 1 }

Write-Host "`n[3/5] POST /api/v1/me/upstream-key/refresh  (Spring → bridge → bot)"
$refresh = Invoke-RestMethod -Uri "$base/api/v1/me/upstream-key/refresh" -Method Post `
    -Headers $auth -ContentType "application/json" `
    -Body (HJson @{ days = 30; merchantName = "E2E Demo $ts" })
$skKey = $refresh.data.key
Write-Host "  raw: $($refresh | ConvertTo-Json -Compress)"
if (-not $skKey -or -not $skKey.StartsWith("sk_")) { Write-Host "REFRESH FAILED"; exit 1 }
if ($skKey -notmatch '^sk_[a-f0-9]{32}$') { Write-Host "BAD KEY FORMAT: $skKey"; exit 1 }
Write-Host "  sk: $($skKey.Substring(0,8))...$($skKey.Substring($skKey.Length-4))"

Write-Host "`n[4/5] POST /api/v1/me/upstream-key/payment-qr/test  (Test API Key page - KHQR card)"
$qr = Invoke-RestMethod -Uri "$base/api/v1/me/upstream-key/payment-qr/test" -Method Post `
    -Headers $auth -ContentType "application/json" `
    -Body (HJson @{ bank = "bakong"; amount = 1.50; currency = "USD" })
$md5 = $qr.data.md5
if (-not $md5) { Write-Host "TEST QR FAILED: $($qr | ConvertTo-Json)"; exit 1 }
$imgPrefix = $qr.data.qrImage.Substring(0, [Math]::Min(40, $qr.data.qrImage.Length))
Write-Host "  md5: $md5  bank: $($qr.data.bank)  amount: $($qr.data.amount) $($qr.data.currency)"
Write-Host "  merchant: $($qr.data.merchantName)"
Write-Host "  qrImage[0..40]: $imgPrefix"
Write-Host "  qrString[0..50]: $($qr.data.qrString.Substring(0, [Math]::Min(50, $qr.data.qrString.Length)))..."

Write-Host "`n[5/5] GET /api/v1/me/upstream-key/payment-status/$md5"
$cp = Invoke-RestMethod -Uri "$base/api/v1/me/upstream-key/payment-status/$md5" `
    -Headers $auth
Write-Host "  status: $($cp.data.status)  paid: $($cp.data.paid)  amount: $($cp.data.amount)"

Write-Host "`n=========================="
Write-Host "ALL GOOD. wizard -> bridge -> bot OK."
Write-Host "  user:  $email"
Write-Host "  sk:    $skKey"
Write-Host "  md5:   $md5"
Write-Host "  status: $($cp.data.status)"
Write-Host "  PDF:   https://apicheckpayment.onrender.com/docs/key.pdf?key=$skKey"
Write-Host "=========================="
