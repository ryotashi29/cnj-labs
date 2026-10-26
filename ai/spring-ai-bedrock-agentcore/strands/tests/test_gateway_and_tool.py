import json
from unittest import mock

from outing_agent import agent, gateway


def test_structuredContent_の_result_に包まれた値も取り出せる():
    assert gateway.payload({"structuredContent": {"result": {"代々木公園": []}}}) == {"代々木公園": []}


def test_テキストの_JSON_からも取り出せる():
    assert gateway.payload({"content": [{"text": json.dumps({"代々木公園": []})}]}) == {"代々木公園": []}


def test_利用者のトークンを渡し_上限を超えると呼ばない():
    with mock.patch.object(gateway, "resolve", return_value={"代々木公園": []}) as resolve:
        tool = agent.build_geocoding_tool("Bearer t")
        for _ in range(agent.MAX_TOOL_CALLS):
            assert "代々木公園" in json.loads(tool(queries=["代々木公園"]))
        assert "error" in json.loads(tool(queries=["代々木公園"]))
    assert resolve.call_count == agent.MAX_TOOL_CALLS
    resolve.assert_called_with(["代々木公園"], "Bearer t")
