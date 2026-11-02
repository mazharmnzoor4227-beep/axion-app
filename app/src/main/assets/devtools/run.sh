#!/bin/bash
# Entry point: run.sh <tool> <install|update|uninstall|version|latest|size>
. /opt/axion/devtools/common.sh
tool="$1"; action="$2"
[ -f "/opt/axion/devtools/$tool.sh" ] || die "Unknown tool $tool"
. "/opt/axion/devtools/$tool.sh"
case "$action" in
  install|update) do_install; v="$(do_version)"; [ -n "$v" ] || die "$tool did not verify after install"; echo "$v" > "$MARK/$tool"; echo "@@V $v"; echo "@@S $(do_size)"; p 100 "Ready" ;;
  uninstall) do_uninstall; rm -f "$MARK/$tool"; echo "@@S 0"; p 100 "Removed" ;;
  version) v="$(do_version)"; echo "@@V ${v:-none}" ;;
  latest) echo "@@L $(do_latest 2>/dev/null || echo unknown)" ;;
  size) echo "@@S $(do_size)" ;;
  *) die "Unknown action $action" ;;
esac
