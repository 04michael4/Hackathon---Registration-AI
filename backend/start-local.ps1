param(
	[switch]$Packaged
)

$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '../.env'
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
	throw 'Create the private root .env using .env.example before starting the backend.'
}

# Parse values as data. Never execute .env contents or print credentials.
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
	$entry = $line.Trim()
	if (-not $entry -or $entry.StartsWith('#')) { continue }
	if ($entry -notmatch '^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
		throw 'The root .env must contain KEY=value entries or comments.'
	}
	$key = $matches[1]
	$value = $matches[2].Trim()
	if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
		$value = $value.Substring(1, $value.Length - 2)
	}
	[Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

if (-not $env:JAVA_HOME) {
	$localJdk = Join-Path $env:ProgramFiles 'Java/jdk-21'
	if (Test-Path -LiteralPath (Join-Path $localJdk 'bin/java.exe')) { $env:JAVA_HOME = $localJdk }
}

Push-Location $PSScriptRoot
try {
	if ($Packaged) {
		$jarPath = Join-Path $PSScriptRoot 'target/registration-backend-0.0.1-SNAPSHOT.jar'
		if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
			throw 'Build the backend with the Maven wrapper before using -Packaged.'
		}
		$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
		& $javaPath -jar $jarPath
	} else {
		# Git Bash avoids the existing Windows wrapper junction-target issue.
		$gitBash = Join-Path $env:ProgramFiles 'Git/bin/bash.exe'
		if (Test-Path -LiteralPath $gitBash -PathType Leaf) {
			& $gitBash ./mvnw spring-boot:run
		} else {
			& ./mvnw.cmd spring-boot:run
		}
	}
	$backendExitCode = $LASTEXITCODE
} finally {
	Pop-Location
}
exit $backendExitCode
