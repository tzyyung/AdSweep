#!/usr/bin/env python3
"""
AdSweep MCP Server — Provides tools for Claude Code to analyze Android APKs.

Tools:
  - scan_apk: Run Layer 2 scanner on a decompiled directory
  - read_smali_class: Read a specific smali class file
  - find_ad_classes: Find all ad-related classes in a decompiled APK
  - extract_methods: Extract method signatures from a smali file
  - generate_rules: Generate suggested rules from scan results
"""
import json
import sys
import os
import glob
import re

# Add injector to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "injector"))


def handle_request(request):
    """Handle a JSON-RPC request."""
    method = request.get("method")
    params = request.get("params", {})
    req_id = request.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "adsweep", "version": "1.0.0"}
            }
        }

    elif method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "tools": [
                    {
                        "name": "scan_apk",
                        "description": "Run Layer 2 scanner on a decompiled APK directory. Returns found SDKs and suggested rules.",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "decompiled_dir": {"type": "string", "description": "Path to decompiled APK directory"}
                            },
                            "required": ["decompiled_dir"]
                        }
                    },
                    {
                        "name": "read_smali_class",
                        "description": "Read a smali class file by class name (e.g., com.example.AdManager)",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "decompiled_dir": {"type": "string"},
                                "class_name": {"type": "string", "description": "Fully qualified class name"}
                            },
                            "required": ["decompiled_dir", "class_name"]
                        }
                    },
                    {
                        "name": "find_ad_classes",
                        "description": "Find all ad-related classes in a decompiled APK, grouped by category",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "decompiled_dir": {"type": "string"}
                            },
                            "required": ["decompiled_dir"]
                        }
                    },
                    {
                        "name": "extract_methods",
                        "description": "Extract all method signatures from a smali file",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "decompiled_dir": {"type": "string"},
                                "class_name": {"type": "string"}
                            },
                            "required": ["decompiled_dir", "class_name"]
                        }
                    },
                    {
                        "name": "generate_rules",
                        "description": "Run scanner and generate suggested rules for a decompiled APK",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "decompiled_dir": {"type": "string"}
                            },
                            "required": ["decompiled_dir"]
                        }
                    }
                ]
            }
        }

    elif method == "tools/call":
        tool_name = params.get("name")
        args = params.get("arguments", {})
        try:
            result = call_tool(tool_name, args)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": result}]}
            }
        except Exception as e:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": f"Error: {e}"}]}
            }

    elif method == "notifications/initialized":
        return None  # No response needed

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"Unknown method: {method}"}
    }


def call_tool(name, args):
    """Execute a tool and return the result as string."""
    decompiled_dir = args.get("decompiled_dir", "")

    if name == "scan_apk":
        from scanner import scan, generate_suggested_rules
        report = scan(decompiled_dir)
        result = {"found_sdks": report["found_sdks"], "summary": report["summary"]}
        return json.dumps(result, indent=2, ensure_ascii=False)

    elif name == "read_smali_class":
        class_name = args["class_name"]
        rel_path = class_name.replace(".", "/") + ".smali"
        for smali_dir in sorted(glob.glob(os.path.join(decompiled_dir, "smali*"))):
            full_path = os.path.join(smali_dir, rel_path)
            if os.path.exists(full_path):
                with open(full_path, "r", errors="ignore") as f:
                    content = f.read()
                # Truncate if too large
                if len(content) > 10000:
                    content = content[:10000] + f"\n\n... truncated ({len(content)} total chars)"
                return content
        return f"Class not found: {class_name}"

    elif name == "find_ad_classes":
        from prepare_for_analysis import find_ad_classes, extract_methods
        categories = find_ad_classes(decompiled_dir)
        result = {}
        for cat, files in categories.items():
            result[cat] = []
            for f in files[:20]:
                rel = os.path.relpath(f, decompiled_dir)
                class_name = rel.replace("/", ".").replace(".smali", "")
                for prefix in ["smali.", "smali_classes2.", "smali_classes3.", "smali_classes4.", "smali_classes5.", "smali_classes6.", "smali_classes7."]:
                    class_name = class_name.removeprefix(prefix)
                methods = extract_methods(f)
                result[cat].append({
                    "class": class_name,
                    "methods": [f"{m['access']} {m['return']} {m['name']}({m['params']})" for m in methods[:10]]
                })
        return json.dumps(result, indent=2, ensure_ascii=False)

    elif name == "extract_methods":
        class_name = args["class_name"]
        rel_path = class_name.replace(".", "/") + ".smali"
        for smali_dir in sorted(glob.glob(os.path.join(decompiled_dir, "smali*"))):
            full_path = os.path.join(smali_dir, rel_path)
            if os.path.exists(full_path):
                from prepare_for_analysis import extract_methods
                methods = extract_methods(full_path)
                return json.dumps(methods, indent=2, ensure_ascii=False)
        return f"Class not found: {class_name}"

    elif name == "generate_rules":
        from scanner import scan, generate_suggested_rules
        report = scan(decompiled_dir)
        suggested = generate_suggested_rules(decompiled_dir, report)
        return json.dumps({"rules": suggested}, indent=2, ensure_ascii=False)

    return f"Unknown tool: {name}"


def main():
    """MCP stdio transport: read JSON-RPC from stdin, write to stdout."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            response = handle_request(request)
            if response is not None:
                sys.stdout.write(json.dumps(response) + "\n")
                sys.stdout.flush()
        except json.JSONDecodeError:
            pass
        except Exception as e:
            error_response = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32603, "message": str(e)}
            }
            sys.stdout.write(json.dumps(error_response) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
