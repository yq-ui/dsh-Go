#!/usr/bin/env python3
"""connect-scan：不需要 raw socket 的端口探活（proot 里 nmap/naabu 拿不到 netlink/raw 时用）。"""
import argparse, json, socket, sys
from concurrent.futures import ThreadPoolExecutor
TOP = [21,22,23,25,53,80,110,143,443,445,587,993,995,1433,1521,2049,2375,3000,3306,3389,5432,6379,8000,8080,8443,8888,9000,9200,11211,27017]
def probe(host, port, timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return port if s.connect_ex((host, port)) == 0 else None
    finally:
        s.close()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('host')
    ap.add_argument('-p', '--ports', default='')
    ap.add_argument('-t', '--timeout', type=float, default=0.8)
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()
    ports = TOP
    if a.ports:
        ports = []
        for part in a.ports.split(','):
            if '-' in part:
                lo, hi = part.split('-'); ports += list(range(int(lo), int(hi) + 1))
            else:
                ports.append(int(part))
    open_ports = []
    with ThreadPoolExecutor(max_workers=64) as ex:
        for r in ex.map(lambda p: probe(a.host, p, a.timeout), ports):
            if r: open_ports.append(r)
    open_ports.sort()
    if a.json:
        print(json.dumps({'host': a.host, 'open': open_ports}))
    else:
        print('%s: %s' % (a.host, ' '.join(str(p) for p in open_ports) or 'no open port'))
    return 0 if open_ports else 1
if __name__ == '__main__':
    sys.exit(main())
