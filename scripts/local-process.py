#!/usr/bin/env python3
"""Deploy, start, or inspect only the Tech Lead planning process on local Camunda."""
import argparse
import json
import os
from pathlib import Path
import urllib.request
import urllib.error

PROCESS_ID = 'implementar_feature_desde_tech_lead_agent_v1'
parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['deploy', 'start', 'inspect'])
parser.add_argument('--ticket')
parser.add_argument('--project', help='Project identifier configured in CAMUNDA_PROJECTS_PATH')
parser.add_argument('--instance')
parser.add_argument('--async', dest='asynchronous', action='store_true',
                    help='Return the instance key immediately instead of waiting for the decisions')
args = parser.parse_args()
base = os.environ.get('CAMUNDA_REST_ADDRESS', 'http://127.0.0.1:8088').rstrip('/')
# This tool deliberately targets only the local, unauthenticated development cluster.
if urllib.parse.urlparse(base).hostname not in ('localhost', '127.0.0.1'):
    parser.error('Only local Camunda is supported by this development helper.')

def request(path, body=None, content_type='application/json', timeout=30):
    data = body if isinstance(body, bytes) else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + '/v2' + path, data=data, headers={'Content-Type': content_type})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)

if args.action == 'deploy':
    bpmn = Path(__file__).resolve().parents[1] / 'bpmn' / 'agents-orchestrator.bpmn'
    boundary = 'TechLeadMvpResourceBoundary'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="resources"; filename="{bpmn.name}"\r\n'
            'Content-Type: application/xml\r\n\r\n').encode() + bpmn.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    result = request('/deployments', body, 'multipart/form-data; boundary=' + boundary)
elif args.action == 'start':
    import re
    if not args.project or not re.fullmatch(r'[a-z0-9-]{1,64}', args.project):
        parser.error('--project is required and must be a configured project identifier')
    if not args.ticket or not re.fullmatch(r'[A-Z][A-Z0-9]*-\d+', args.ticket):
        parser.error('--ticket must be a real Jira issue key, e.g. EXAMPLE-123')
    payload = {'processDefinitionId': PROCESS_ID,
               'variables': {'projectId': args.project, 'ticketKey': args.ticket}}
    if not args.asynchronous:
        payload.update(awaitCompletion=True, requestTimeout=600000, fetchVariables=['impactAnalysis'])
    try:
        result = request('/process-instances', payload, timeout=630 if not args.asynchronous else 30)
    except urllib.error.HTTPError as error:
        if error.code == 504:
            parser.exit(1, 'Camunda agotó la espera HTTP; el proceso puede seguir ejecutándose. '
                           'Revisar Operate antes de volver a iniciar para evitar duplicados.\n')
        raise
    if not args.asynchronous and not isinstance(result.get('variables', {}).get('impactAnalysis'), dict):
        parser.exit(1, 'El proceso terminó sin impactAnalysis. Revisar el fin por timeout en Operate.\n')
else:
    if not args.instance or not args.instance.isdigit():
        parser.error('--instance is required and must be numeric')
    result = {'process': request('/process-instances/' + args.instance)}
    variables = request('/variables/search', {'filter': {'processInstanceKey': args.instance, 'name': 'impactAnalysis'}})
    result['analysis'] = []
    for variable in variables.get('items', []):
        full = request('/variables/' + variable['variableKey'])
        result['analysis'].append(json.loads(full['value']))
print(json.dumps(result, indent=2, ensure_ascii=False))
