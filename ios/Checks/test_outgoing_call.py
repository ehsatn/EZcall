"""Check outgoing UUID routing and presentation on hosts without iOS frameworks."""

from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "ezcall"


def method(source, signature):
    return source.split(signature, 1)[1].split("\n    }", 1)[0]


class OutgoingCallCheck(unittest.TestCase):
    def setUp(self):
        self.relay = (SOURCE / "RelayController.swift").read_text(encoding="utf-8")
        self.app = (SOURCE / "EzcallApp.swift").read_text(encoding="utf-8")
        self.view = (SOURCE / "InCallView.swift").read_text(encoding="utf-8")

    def test_first_tap_reserves_the_uuid_used_by_callkit_and_android(self):
        dial = method(self.relay, "func dial(_ number: String)")
        prepare = method(self.relay, "func prepareOutgoingDial(")
        request = method(self.relay, "func requestOutgoingCall(")
        start = method(self.relay, "func callDial(")
        self.assertIn("phoneCall == nil", dial)
        self.assertLess(dial.index("prepareOutgoingDial(id"), dial.index("requestOutgoingCall(id"))
        self.assertIn("phoneCall = PhoneCall(id: id", prepare)
        self.assertIn("CXStartCallAction(call: id,", request)
        self.assertIn("outgoingDial?.id == id", start)
        self.assertIn("outgoingDial?.sent == false", start)
        self.assertIn('"id": id.uuidString.lowercased()', start)

    def test_phone_recents_can_supply_a_fresh_uuid(self):
        dial = method(self.relay, "func dial(_ number: String)")
        start = method(self.relay, "func callDial(")
        self.assertIn("outgoingCallIDs.insert(id)", dial)
        self.assertIn("outgoingDial == nil, phoneCall == nil", start)
        self.assertLess(start.index("outgoingCallIDs.insert(id).inserted"), start.index("prepareOutgoingDial(id"))

    def test_callkit_dismissal_preserves_sim_call_and_its_end_control(self):
        self.assertIn("outgoingDial?.id != id", method(self.relay, "func phoneCallEnded("))
        end = method(self.relay, "func endPhoneCall(")
        self.assertIn("call.id == id", end)
        self.assertIn("callHangUp(id)", end)
        route = method(self.relay, "func route(")
        active, ended = route.split("case .active:")[1].split("case .ended:")
        self.assertIn("phoneCallConnected(id", active)
        self.assertIn("closeOutgoingDial(id)", ended)
        self.assertIn("outgoingDial?.id == id", method(self.relay, "func closeOutgoingDial("))

    def test_outgoing_waits_for_same_call_during_bluetooth_recovery(self):
        self.assertIn("if let outgoing = sentOutgoingCall", method(self.relay, "func beginCallRecovery("))
        events = method(self.relay, "func handle(_ header:")
        self.assertIn("if matchesOutgoing, callReconnecting, call.state == .active", events)
        self.assertIn("!outgoingCallIDs.contains(id) || sentOutgoingCall?.id == id", events)
        self.assertIn("sentOutgoingCall?.id == id || queuedAnswer == id || calls.has(id) else { return }", events)

    def test_window_and_minimize_use_the_current_call_uuid(self):
        self.assertGreaterEqual(self.app.count("presentVisibleCall()"), 3)
        self.assertIn("relay.phoneCall?.id == id", method(self.app, "func updateCallWindow()"))
        self.assertIn("call.id == minimizedCall", self.app)
        self.assertIn("call.id == callID", self.view)
        self.assertIn("relay.endPhoneCall(call.id)", self.view)


if __name__ == "__main__":
    unittest.main()
