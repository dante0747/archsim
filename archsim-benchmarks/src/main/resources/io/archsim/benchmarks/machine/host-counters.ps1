
$c = @('\Processor Information(_Total)\Actual Frequency', '\Processor Information(_Total)\% Performance Limit', '\Processor Information(_Total)\Performance Limit Flags', '\Hyper-V Hypervisor Logical Processor(*)\% Total Run Time')
while ($true) {
  $s = Get-Counter -Counter $c -SampleInterval 1 -MaxSamples 1 -ErrorAction SilentlyContinue
  $o = @{ t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
  foreach ($x in $s.CounterSamples) { $p = $x.Path.Split('\'); $o[$p[-2] + '|' + $p[-1]] = [math]::Round($x.CookedValue, 2) }
  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))
  [Console]::Out.Flush()
}
