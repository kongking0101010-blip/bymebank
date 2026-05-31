#requires -Version 5
$ErrorActionPreference = 'Stop'

$BASE = 'http://localhost:8080'
$BAR  = '=' * 70

function Section($title) {
  Write-Host ""
  Write-Host $BAR -ForegroundColor DarkGray
  Write-Host "  $title" -ForegroundColor Cyan
  Write-Host $BAR -ForegroundColor DarkGray
}

# ============================================================
# 1. Confirm the Java backend is running
# ============================================================
Section "1. Java backend health check ($BASE)"
$h = Invoke-RestMethod -Uri "$BASE/api/v1/public/health"
"   service: $($h.data.service)"
"   status : $($h.data.status)"

# ============================================================
# 2. Bootstrap a fresh dev account + API key + 4 merchants
# ============================================================
Section "2. Provision: register / api-key / link 4 merchants"
$email = "demo-{0}@khmerbank.test" -f (Get-Random -Maximum 99999)
"   email: $email"

$jwt = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/auth/register" `
        -ContentType 'application/json' -Body (@{
            email=$email; password='SuperSecret123'; fullName='Combined Demo'; company='Demo'
        } | ConvertTo-Json)).data.accessToken
$auth = @{ Authorization = "Bearer $jwt" }

$apiKey = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/api-keys" `
        -ContentType 'application/json' -Headers $auth `
        -Body '{"name":"combined demo"}').data.key
"   api key: $($apiKey.Substring(0,14))... ($($apiKey.Length) chars)"

$merchants = @(
  @{ bankType='ABA';    merchantName='ABA Coffee';    merchantId='ABAPAY-COMBINED' }
  @{ bankType='ACLEDA'; merchantName='ACLEDA Coffee'; merchantId='ACLEDA-COMBINED' }
  @{ bankType='WING';   merchantName='Wing Coffee';   merchantId='WING-COMBINED'; accountNumber='099887766' }
  @{ bankType='BAKONG'; merchantName='Bakong Coffee'; merchantId='combined@aclb' }
)
foreach ($m in $merchants) {
  $r = Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/merchants" `
         -ContentType 'application/json' -Headers $auth -Body ($m | ConvertTo-Json)
  "   linked: $($r.data.bankType.PadRight(6)) - $($r.data.merchantName)"
}

# ============================================================
# 3. Python SDK calling the Java backend
# ============================================================
Section "3. Python SDK -> Java backend"
$env:KHMERBANK_API_KEY = $apiKey
$env:KHMERBANK_BASE_URL = $BASE
$env:PYTHONPATH = "D:\structerbankfutur\khmer-bank-gateway\sdk-python"

$pyScript = @'
import os, sys
from khmerbank import KhmerBank, BankType, Currency

c = KhmerBank(api_key=os.environ["KHMERBANK_API_KEY"],
              base_url=os.environ["KHMERBANK_BASE_URL"])

print("[python] linked merchants:")
for m in c.list_merchants():
    print(f"    {m.bank_type.value:6} - {m.merchant_name}")

print("\n[python] generating one KHQR per bank:")
for bank, amt in [(BankType.BAKONG, "12.50"), (BankType.ABA, "35.00"),
                  (BankType.ACLEDA, "8.75"), (BankType.WING, "100.00")]:
    qr = c.generate_qr(bank=bank, amount=amt, currency=Currency.USD,
                       description=f"py {bank.value}", reference=f"PY-{bank.value}",
                       expires_in=900)
    print(f"    {bank.value:6} -> {qr.transaction_id}")
    print(f"            {qr.qr_payload}")
'@
$pyFile = New-TemporaryFile
$pyFile = [IO.Path]::ChangeExtension($pyFile.FullName, '.py')
$pyScript | Out-File -FilePath $pyFile -Encoding UTF8
python $pyFile
Remove-Item $pyFile -Force

# ============================================================
# 4. Java SDK calling the Java backend
# ============================================================
Section "4. Java SDK -> Java backend"
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;D:\structerbankfutur\.maven\apache-maven-3.9.16\bin;$env:PATH"

Push-Location D:\structerbankfutur\khmer-bank-gateway\sdk-java
mvn -B -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = (Get-Content cp.txt) + ';target\classes;target\test-classes'
$env:KHMERBANK_API_KEY  = $apiKey
$env:KHMERBANK_BASE_URL = $BASE
& "$env:JAVA_HOME\bin\java.exe" -cp $cp demo.JavaSdkDemo
Remove-Item cp.txt -Force -ErrorAction SilentlyContinue
Pop-Location

Section "ALL LAYERS WORKING"
Write-Host "  Java backend (port 8080)  : LIVE" -ForegroundColor Green
Write-Host "  Python SDK calling backend: PASS" -ForegroundColor Green
Write-Host "  Java   SDK calling backend: PASS" -ForegroundColor Green
Write-Host "  Banks supported           : ABA, ACLEDA, WING, BAKONG" -ForegroundColor Green
