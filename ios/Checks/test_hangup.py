"""Check the SIM hangup call sites on hosts without CallKit."""

from pathlib import Path
import re
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "ezcall"


def method(source, signature):
    return source.split(signature, 1)[1].split("\n    }", 1)[0]


class HangupCheck(unittest.TestCase):
    def setUp(self):
        self.bridge = (SOURCE / "CallBridge.swift").read_text(encoding="utf-8")
        self.relay = (SOURCE / "RelayController.swift").read_text(encoding="utf-8")

    def test_only_end_action_can_request_sim_hangup(self):
        end = method(self.bridge, "perform action: CXEndCallAction)")
        self.assertEqual(self.bridge.count(".callHangUp("), 1)
        self.assertIn("relay?.callHangUp(id)", end)
        expired, forward = end.split("if answerExpired && !fromApp {", 1)[1].split("} else {", 1)
        self.assertIn("relay?.callPresentationEnded(id)", expired)
        self.assertIn("relay?.callHangUp(id)", forward)

    def test_calls_are_matched_by_uuid_when_callkit_handles_more_than_one(self):
        observer = method(self.bridge, "func callObserver(")
        self.assertIn("guard entries[call.uuid] != nil else { return }", observer)

        end = method(self.bridge, "perform action: CXEndCallAction)")
        self.assertIn("let id = action.callUUID", end)
        self.assertIn("if let entry = entries.removeValue(forKey: id)", end)

    def test_outgoing_calls_always_publish_capabilities_and_reject_hold(self):
        start = method(self.bridge, "perform action: CXStartCallAction)")
        self.assertRegex(start, r"\n        provider\.reportCall\(with: id, updated: update\(")
        self.assertIn("update.supportsHolding = false", method(self.bridge, "func update("))
        self.assertIn("action.fail()", method(self.bridge, "perform action: CXSetHeldCallAction)"))

    def test_local_cleanup_cannot_send_or_queue_sim_hangup(self):
        for signature in ("func callObserver(", "timedOutPerforming action: CXAction)"):
            with self.subTest(callback=signature):
                self.assertIn("relay?.callPresentationEnded(", method(self.bridge, signature))

        hangup = method(self.relay, "func callHangUp(")
        self.assertIn("pendingHangUp = (id, recoveryDeadline)", hangup)
        self.assertEqual(re.findall(r'pendingHangUp = (?!nil\b).*', self.relay),
                         ["pendingHangUp = (id, recoveryDeadline)"])
        self.assertIn('send(type: "call_end", id: id.uuidString.lowercased())', hangup)
        self.assertEqual(self.relay.count('"call_end"'), 2)
        self.assertIn('if ContinuousClock.now < pendingHangUp.deadline {', self.relay)
        self.assertIn('send(type: "call_end", id: pendingHangUp.id.uuidString.lowercased())', self.relay)


if __name__ == "__main__":
    unittest.main()
