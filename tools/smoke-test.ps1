<#
  RentRadar end to end smoke test.

  Registers a user, logs in, submits a property and asks for a prediction,
  printing whatever comes back. Any failure prints the server's own error body
  and the request that caused it, which is the part that tells you what to fix.

  Needs all three running: Mongo in docker, the Python service on 8000, and the
  gateway on 8080.

      .\tools\smoke-test.ps1
#>

$ErrorActionPreference = "Stop"

$base     = "http://localhost:8080/api/v1"
$market   = "GH"
$email    = "smoke$(Get-Random -Maximum 99999)@vvu.edu.gh"
$password = "SmokeTestPassword123"

function Invoke-Api {
    param([string]$Url, [hashtable]$Body, [string]$Token)

    $json = $Body | ConvertTo-Json -Depth 6
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    try {
        if ($headers.Count -gt 0) {
            return Invoke-RestMethod -Uri $Url -Method Post -Body $json `
                   -ContentType "application/json" -Headers $headers
        }
        return Invoke-RestMethod -Uri $Url -Method Post -Body $json `
               -ContentType "application/json"
    }
    catch {
        Write-Host ""
        Write-Host "FAILED: POST $Url" -ForegroundColor Red
        $resp = $_.Exception.Response
        if ($resp) {
            Write-Host ("status " + [int]$resp.StatusCode) -ForegroundColor Red
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            Write-Host $reader.ReadToEnd()
        } else {
            Write-Host $_.Exception.Message -ForegroundColor Red
        }
        Write-Host ""
        Write-Host "The request body was:"
        Write-Host $json
        exit 1
    }
}

Write-Host "1. register  $email"
Invoke-Api "$base/auth/register" @{
    email    = $email
    password = $password
    role     = "TENANT"
    marketId = $market
} | Out-Null
Write-Host "   created"

Write-Host "2. log in"
$auth = Invoke-Api "$base/auth/login" @{
    email    = $email
    password = $password
}
$token = $auth.accessToken
if (-not $token) { Write-Host "no accessToken in the login response" -ForegroundColor Red; exit 1 }
Write-Host "   token received"

# Price is deliberately far below what East Legon supports, so the fraud
# detector has something real to react to.
Write-Host "3. submit a property, asking 4,200 GHS in East Legon"
$created = Invoke-Api "$base/properties" @{
    marketId     = $market
    city         = "Accra"
    district     = "East Legon"
    title        = "3 bedroom apartment, East Legon"
    bedrooms     = 3
    bathrooms    = 3
    toilets      = 4
    propertyType = "Apartment"
    size         = 180
    sizeUnit     = "SQM"
    price        = 4200
    currency     = "GHS"
    furnished    = $true
    amenities    = @("Swimming Pool", "Security")
    source       = "USER_SUBMITTED"
} $token

$propertyId = $created.id
if (-not $propertyId) { $propertyId = $created.propertyId }
if (-not $propertyId) {
    Write-Host "could not find an id in the property response:" -ForegroundColor Red
    $created | ConvertTo-Json -Depth 8
    exit 1
}
Write-Host "   property $propertyId"

Write-Host "4. ask for a prediction"
$prediction = Invoke-Api "$base/predictions" @{ propertyId = $propertyId } $token

Write-Host ""
Write-Host "================ PREDICTION ================"
$prediction | ConvertTo-Json -Depth 8