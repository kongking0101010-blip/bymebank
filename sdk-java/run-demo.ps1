#requires -Version 5
$ErrorActionPreference = 'Stop'
$BASE = 'http://localhost:8080'

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;D:\structerbankfutur\.maven\apache-maven-3.9.16\bin;$env:PATH"

# Provision a fresh account & API key for this run
$email = "java-demo-{0}@k.test" -f (Get-Random -Maximum 99999)
$jwt = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/auth/register" `
        -ContentType 'application/json' -Body (@{
            email=$email; password='SuperSecret123'; fullName='Java Demo'; company='Demo'
        } | ConvertTo-Json)).data.accessToken
$apiKey = (Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/api-keys" `
        -ContentType 'application/json' -Headers @{Authorization="Bearer $jwt"} `
        -Body '{"name":"java demo"}').data.key

# Link 4 merchants
$merchants = @(
  '{"bankType":"ABA","merchantName":"ABA Coffee","merchantId":"ABAPAY-JV"}',
  '{"bankType":"ACLEDA","merchantName":"ACLEDA Coffee","merchantId":"ACLEDA-JV"}',
  '{"bankType":"WING","merchantName":"Wing Coffee","merchantId":"WING-JV","accountNumber":"011223344"}',
  '{"bankType":"BAKONG","merchantName":"Bakong Coffee","merchantId":"javademo@aclb"}'
)
foreach ($m in $merchants) {
  Invoke-RestMethod -Method POST -Uri "$BASE/api/v1/merchants" `
    -ContentType 'application/json' -Headers @{Authorization="Bearer $jwt"} -Body $m | Out-Null
}

# Build classpath then run
mvn -B -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = (Get-Content cp.txt) + ';target\classes;target\test-classes'

$env:KHMERBANK_API_KEY  = $apiKey
$env:KHMERBANK_BASE_URL = $BASE

& "$env:JAVA_HOME\bin\java.exe" -cp $cp demo.JavaSdkDemo

Remove-Item cp.txt -Force -ErrorAction SilentlyContinue
