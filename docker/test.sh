#!/usr/bin/env bash
# 뷰어 API 통합 테스트. seed.sh 실행 후 사용.
set -uo pipefail
FAIL=0
check() { # 설명 기대값 실제값
  if [[ "$2" == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: expected [$2] got [$3]"; FAIL=1; fi
}
enc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$1"; }
jq_() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

run() { # base-url [user:pass]
  local B="$1" A=()
  [[ -n "${2:-}" ]] && A=(-u "$2")
  echo "== $B"
  local C=(curl -s "${A[@]}")

  if [[ -n "${2:-}" ]]; then
    check "인증 없으면 401" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$B/api/buckets")"
    check "잘못된 비밀번호 401" 401 "$(curl -s -u admin:wrong -o /dev/null -w '%{http_code}' "$B/api/buckets")"
  fi
  check "UI 200" 200 "$("${C[@]}" -o /dev/null -w '%{http_code}' "$B/")"
  check "POST 405" 405 "$("${C[@]}" -X POST -o /dev/null -w '%{http_code}' "$B/api/buckets")"
  check "DELETE 405" 405 "$("${C[@]}" -X DELETE -o /dev/null -w '%{http_code}' "$B/api/object?bucket=test-bucket&key=data.json")"
  check "버킷 목록" "second.bucket,test-bucket" "$("${C[@]}" "$B/api/buckets" | jq_ '",".join(sorted(b["name"] for b in d["buckets"]))')"

  local L; L="$("${C[@]}" "$B/api/list?bucket=test-bucket")"
  check "루트 폴더" "deep/,many/,with meta/" "$(echo "$L" | jq_ '",".join(d["prefixes"])')"
  check "루트 파일 수" 8 "$(echo "$L" | jq_ 'len(d["objects"])')"
  check "특수문자 키 포함" True "$(echo "$L" | jq_ '"100% done#?.log" in [o["key"] for o in d["objects"]] and "한글 파일 (1).txt" in [o["key"] for o in d["objects"]]')"

  check "1페이지 1000개" "1000 True" "$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=many/" | jq_ 'str(len(d["objects"]))+" "+str(d["truncated"])')"
  local T; T="$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=many/" | jq_ 'd["nextToken"]')"
  check "2페이지 205개" "205 False" "$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=many/&token=$(enc "$T")" | jq_ 'str(len(d["objects"]))+" "+str(d["truncated"])')"
  check "접두사 검색" 10 "$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=many/item-010" | jq_ 'len(d["objects"])')"
  check "깊은 폴더" "deep/a/b/c/leaf.txt" "$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=deep/a/b/c/" | jq_ 'd["objects"][0]["key"]')"
  check "delimiter 없이 재귀" 1 "$("${C[@]}" "$B/api/list?bucket=test-bucket&prefix=deep/&delimiter=" | jq_ 'len(d["objects"])')"

  for k in "한글 파일 (1).txt" "a+b=c&d.txt" "100% done#?.log" "with meta/data.json" "deep/a/b/c/leaf.txt"; do
    check "다운로드 [$k]" 200 "$("${C[@]}" -o /dev/null -w '%{http_code}' "$B/api/object?bucket=test-bucket&key=$(enc "$k")")"
  done
  check "본문 일치" "plus" "$("${C[@]}" "$B/api/object?bucket=test-bucket&key=$(enc 'a+b=c&d.txt')")"
  check "Content-Disposition" "attachment; filename=\"__ __ (1).txt\"; filename*=UTF-8''%ED%95%9C%EA%B8%80%20%ED%8C%8C%EC%9D%BC%20%281%29.txt" \
    "$("${C[@]}" -D - -o /dev/null "$B/api/object?bucket=test-bucket&key=$(enc '한글 파일 (1).txt')" | grep -i '^content-disposition' | cut -d' ' -f2- | tr -d '\r')"
  check "빈 파일" "200 0" "$("${C[@]}" -o /dev/null -w '%{http_code} %{size_download}' "$B/api/object?bucket=test-bucket&key=empty.txt")"
  check "대용량 바이트 수" 3000000 "$("${C[@]}" -o /dev/null -w '%{size_download}' "$B/api/object?bucket=test-bucket&key=random.bin")"
  check "Range 206" "206 100" "$("${C[@]}" -H 'Range: bytes=100-199' -o /dev/null -w '%{http_code} %{size_download}' "$B/api/object?bucket=test-bucket&key=random.bin&inline=1")"
  check "HTML inline 은 text/plain + sandbox" "text/plain; charset=utf-8|sandbox" \
    "$("${C[@]}" -D - -o /dev/null "$B/api/object?bucket=test-bucket&key=evil.html&inline=1" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type"{t=$2} tolower($1)=="content-security-policy"{c=$2} END{print t"|"c}')"
  check "png inline 은 image/png" "image/png" \
    "$("${C[@]}" -D - -o /dev/null "$B/api/object?bucket=test-bucket&key=pixel.png&inline=1" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type"{print $2}')"
  check "head 메타데이터" "demo tester" "$("${C[@]}" "$B/api/head?bucket=test-bucket&key=$(enc 'with meta/data.json')" | jq_ 'd["metadata"]["purpose"]+" "+d["metadata"]["owner"]')"
  check "head 크기" 3000000 "$("${C[@]}" "$B/api/head?bucket=test-bucket&key=random.bin" | jq_ 'd["size"]')"
  check "없는 키 404" 404 "$("${C[@]}" -o /dev/null -w '%{http_code}' "$B/api/object?bucket=test-bucket&key=nope.txt")"
  check "없는 키 head 404" 404 "$("${C[@]}" -o /dev/null -w '%{http_code}' "$B/api/head?bucket=test-bucket&key=nope.txt")"
  check "없는 버킷" True "$("${C[@]}" "$B/api/list?bucket=no-such-bucket" | jq_ '"error" in d')"
  check "점 포함 버킷" "only.json" "$("${C[@]}" "$B/api/list?bucket=second.bucket" | jq_ 'd["objects"][0]["key"]')"
  check "bucket 누락 400" 400 "$("${C[@]}" -o /dev/null -w '%{http_code}' "$B/api/list")"
}

run http://localhost:18080
run http://localhost:18081 admin:admin

echo "== 버킷 허용 목록 (http://localhost:18082, S3_BUCKETS=test-bucket)"
check "고정 버킷만 표시" "test-bucket" "$(curl -s http://localhost:18082/api/buckets | jq_ '",".join(b["name"] for b in d["buckets"])')"
check "허용 버킷 조회" 200 "$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18082/api/list?bucket=test-bucket')"
check "비허용 버킷 403" 403 "$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18082/api/list?bucket=second.bucket')"
check "비허용 버킷 객체 403" 403 "$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18082/api/object?bucket=second.bucket&key=only.json')"
[[ $FAIL == 0 ]] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
