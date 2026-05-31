$ErrorActionPreference = 'Stop'
$BASE = 'http://localhost:8080'
$VPS  = 'https://apicheckpayment.onrender.com'
$VPS_KEY = 'sk_089ab624449043c21d0c2f4ad606c814'

function S($t) { Write-Host ""; Write-Host ('=' * 72) -ForegroundColor DarkGray; Write-Host "  $t" -ForegroundColor Cyan; Write-Host ('=' * 72) -ForegroundColor DarkGray }

# 1. Register a fresh user (= "user buys access")
S "1. NEW USER signs up"
$email = "buyer-{0}@khmerbank.test" -f (Get-Random -Maximum 99999)
$jwt = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/auth/register" `
        -ContentType 'application/json' -Body (@{
            email=$email; password='SuperSecret123'; fullName='Buyer Demo'; company='Demo'
        } | ConvertTo-Json)).data.accessToken
$auth = @{ Authorization = "Bearer $jwt" }
"   email: $email"

# 2. The user "pays" — we activate a plan (in real life, after VPS reports PAID).
S "2. USER buys BASIC plan -> we activate subscription"
$sub = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/subscriptions/upgrade?plan=BASIC&paymentTxnId=DEMO-PAID" `
        -Headers $auth).data
"   plan         : $($sub.plan)"
"   monthlyQuota : $($sub.monthlyQuota)"
"   active       : $($sub.active)"

# 3. Mint the API key (this is what the user will use from their server)
S "3. USER receives API key"
$apiKey = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/api-keys" `
            -ContentType 'application/json' -Headers $auth `
            -Body '{"name":"my-server-2026"}').data.key
"   API key: $apiKey"

# 4. Link bank accounts
S "4. USER links 4 bank accounts"
$banks = @(
  @{ bankType='BAKONG'; merchantName='SOKSITCHEY HUT'; merchantId='hut_soksitchey1@aclb' },
  @{ bankType='ACLEDA'; merchantName='HUT SOKSITCHEY'; merchantId='ACLEDA-MAIN'; accountNumber='85521063542' },
  @{ bankType='WING';   merchantName='SOKSITCHEY HUT'; merchantId='WING-MAIN';   accountNumber='103345248' },
  @{ bankType='ABA';    merchantName='SOKSITCHEY HUT'; merchantId='ABAPAY-MAIN'; merchantLink='https://link.payway.com.kh/ABAPAYAE446534E' }
)
foreach ($b in $banks) {
  $r = Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/merchants" `
        -ContentType 'application/json' -Headers $auth -Body ($b | ConvertTo-Json)
  "   linked: $($r.data.bankType.PadRight(6)) -> $($r.data.merchantName)"
}

# 5. Generate KHQR for each bank using the API key
S "5. USER's server uses API key to generate KHQR per bank"
$results = @()
foreach ($b in @('BAKONG','ACLEDA','WING','ABA')) {
  try {
    $body = @{
      bankType = $b
      amount = 0.10
      currency = 'USD'
      description = "Test $b 0.10"
      reference = "TEST-$b"
    } | ConvertTo-Json
    $qr = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/payments/qr" `
            -ContentType 'application/json' -Headers @{ 'X-API-Key' = $apiKey } -Body $body).data
    $results += [pscustomobject]@{ bank=$b; txn=$qr.transactionId; md5=$qr.md5; payload=$qr.qrPayload }
    Write-Host ("   [{0}]" -f $b) -ForegroundColor Yellow
    "      txn    : $($qr.transactionId)"
    "      md5    : $($qr.md5)"
    "      payload: $($qr.qrPayload)"
  } catch {
    "   [$b] ERROR: $($_.ErrorDetails.Message)"
  }
}

# 6. Check Bakong payment status via VPS (only Bakong is wired to VPS check-payment)
S "6. CHECK Bakong payment status via VPS"
$bakong = $results | Where-Object { $_.bank -eq 'BAKONG' } | Select-Object -First 1
if ($bakong) {
  Write-Host "   gateway -> VPS  /api/check-payment ?md5=$($bakong.md5)"
  $r = Invoke-RestMethod -Uri "$BASE/api/v1/payments/$($bakong.txn)/status" -Headers @{ 'X-API-Key' = $apiKey }
  "   status      : $($r.data.status)  paid=$($r.data.paid)"
  "   (this means our backend just queried $VPS with our sk_ key)"

  Write-Host "`n   Cross-check, calling VPS directly:" -ForegroundColor Yellow
  $direct = Invoke-RestMethod -Uri "$VPS/api/check-payment?md5=$($bakong.md5)&key=$VPS_KEY"
  "   VPS direct  : paid=$($direct.paid)  status=$($direct.status)"
}

S "DONE"
"   API KEY for this user (save it): $apiKey"
"   sk_ key gateway uses to talk to VPS: $VPS_KEY"
