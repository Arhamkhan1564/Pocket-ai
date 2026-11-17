from pathlib import Path
from collections import Counter
import sys,re,json,hashlib,ipaddress
path=Path(sys.argv[1]);lines=path.read_text().splitlines();families=Counter();ipc=[];loopback=[];outbound=[];unclassified=[];local=[]
for line in lines:
 match=re.search(r'socket\((AF_[A-Z0-9]+),',line)
 if match:families[match.group(1)]+=1
 if not re.search(r'\b(?:connect|sendto|sendmsg|sendmmsg|bind)\(',line):continue
 if 'AF_UNIX' in line:ipc.append(line);continue
 if 'AF_NETLINK' in line:local.append(line);continue
 if 'AF_INET' not in line:unclassified.append(line);continue
 addresses=re.findall(r'inet_addr\("([^"]+)"\)|inet_pton\(AF_INET6, "([^"]+)"',line)
 ips=[ipaddress.ip_address(a or b) for a,b in addresses]
 if 'bind(' in line:local.append(line)
 elif ips and all(x.is_loopback or x.is_unspecified for x in ips):loopback.append(line)
 elif ips:outbound.append(line)
 else:unclassified.append(line)
report={'trace_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'scope':'External Linux Robolectric JUnit JVM and its child threads under strace -f -e trace=network; not Gradle processes, Android airplane mode, or a phone network audit.','socket_family_counts':dict(families),'local_unix_operations':ipc,'local_kernel_or_bind_operations':local,'loopback_ip_operations':loopback,'outbound_ip_connect_or_send_operations':outbound,'unclassified_network_operations':unclassified,'outbound_ip_operations_observed':bool(outbound),'requires_further_network_review':bool(outbound or unclassified),'interpretation':'No observed outbound IP connect/send operations in this traced scope.' if not outbound and not unclassified else 'Inspect the recorded outbound/unclassified operations before making an offline claim.'}
out=path.with_suffix('.summary.json');out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
