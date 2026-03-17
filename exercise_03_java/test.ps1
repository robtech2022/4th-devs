# Test script for local testing
# Make sure the server is running first (mvn exec:java)

# Test 1: Hello message
Write-Host "`n=== Test 1: Hello message ===" -ForegroundColor Cyan
$response1 = Invoke-RestMethod -Uri "http://localhost:8080/" -Method Post -ContentType "application/json" -Body '{"sessionID":"test123","msg":"Hello!"}'
Write-Host "Response: $($response1.msg)" -ForegroundColor Green

# Test 2: Check package
Write-Host "`n=== Test 2: Check package ===" -ForegroundColor Cyan
$response2 = Invoke-RestMethod -Uri "http://localhost:8080/" -Method Post -ContentType "application/json" -Body '{"sessionID":"test123","msg":"Can you check package PKG12345678?"}'
Write-Host "Response: $($response2.msg)" -ForegroundColor Green

# Test 3: Casual conversation
Write-Host "`n=== Test 3: Casual conversation ===" -ForegroundColor Cyan
$response3 = Invoke-RestMethod -Uri "http://localhost:8080/" -Method Post -ContentType "application/json" -Body '{"sessionID":"test123","msg":"What''s your favorite food?"}'
Write-Host "Response: $($response3.msg)" -ForegroundColor Green

# Test 4: Different session
Write-Host "`n=== Test 4: Different session ===" -ForegroundColor Cyan
$response4 = Invoke-RestMethod -Uri "http://localhost:8080/" -Method Post -ContentType "application/json" -Body '{"sessionID":"operator2","msg":"Hi there!"}'
Write-Host "Response: $($response4.msg)" -ForegroundColor Green

Write-Host "`n=== All tests completed ===" -ForegroundColor Yellow
