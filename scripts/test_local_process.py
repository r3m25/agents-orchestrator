"""Client contract checks with an explicitly mocked Camunda HTTP transport."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

SCRIPT = Path(__file__).with_name('local-process.py')


class StartResponseTest(unittest.TestCase):
    def execute(self, args, response=None, error=None):
        output = io.StringIO()
        with patch('sys.argv', [str(SCRIPT), 'start', '--project', 'sample-project', '--ticket', 'SAMPLE-34', *args]), \
             patch.dict('os.environ', {'CAMUNDA_REST_ADDRESS': 'http://127.0.0.1:8088'}), \
             patch('urllib.request.urlopen') as transport, contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            if error:
                transport.side_effect = error
            else:
                transport.return_value.__enter__.return_value = io.BytesIO(json.dumps(response).encode())
            try:
                runpy.run_path(str(SCRIPT), run_name='__main__')
            except SystemExit as failure:
                return transport, output.getvalue(), failure
            return transport, output.getvalue(), None

    def test_returns_decisions_from_same_request(self):
        analysis = {'ticket': 'SAMPLE-34', 'status': 'BLOCKED', 'decisions': ['Request more evidence'],
                    'tasks': [], 'blockers': ['Repository identity mismatch']}
        transport, output, error = self.execute([], {'processInstanceKey': '123', 'variables': {'impactAnalysis': analysis}})
        self.assertIsNone(error)
        self.assertEqual(analysis, json.loads(output)['variables']['impactAnalysis'])
        request = json.loads(transport.call_args.args[0].data)
        self.assertEqual(True, request['awaitCompletion'])
        self.assertEqual(['impactAnalysis'], request['fetchVariables'])
        self.assertGreater(transport.call_args.kwargs['timeout'] * 1000, request['requestTimeout'])
        self.assertEqual(1, transport.call_count)

    def test_preserves_explicit_asynchronous_start(self):
        transport, output, error = self.execute(['--async'], {'processInstanceKey': '123', 'variables': {}})
        self.assertIsNone(error)
        self.assertEqual('123', json.loads(output)['processInstanceKey'])
        self.assertNotIn('awaitCompletion', json.loads(transport.call_args.args[0].data))

    def test_rejects_completion_without_analysis(self):
        transport, output, error = self.execute([], {'processInstanceKey': '123', 'variables': {}})
        self.assertIsNotNone(error)
        self.assertEqual(1, error.code)
        self.assertEqual('', output)
        self.assertEqual(1, transport.call_count)

    def test_does_not_start_another_instance_after_gateway_timeout(self):
        transport, output, error = self.execute([], error=HTTPError('http://127.0.0.1:8088', 504, 'Timeout', None, None))
        self.assertIsNotNone(error)
        self.assertEqual(1, error.code)
        self.assertEqual('', output)
        self.assertEqual(1, transport.call_count)

    def test_rejects_start_without_explicit_project_before_http_call(self):
        transport, output, error = self.execute(['--project', ''])
        self.assertIsNotNone(error)
        self.assertEqual(2, error.code)
        transport.assert_not_called()

    def test_uses_requested_project_instead_of_a_default(self):
        transport, output, error = self.execute(['--project', 'parking-app', '--ticket', 'PARK-7'],
                {'processInstanceKey': '123', 'variables': {'impactAnalysis': {'ticket': 'PARK-7'}}})
        self.assertIsNone(error)
        request = json.loads(transport.call_args.args[0].data)
        self.assertEqual({'projectId': 'parking-app', 'ticketKey': 'PARK-7'}, request['variables'])


if __name__ == '__main__':
    unittest.main()
