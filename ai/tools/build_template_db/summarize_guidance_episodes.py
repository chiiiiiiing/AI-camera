"""Summarize controlled controller replays; these are not aesthetic or device results."""
import argparse
import json
from pathlib import Path


def summarize(data):
    if data.get('evidence_kind') != 'controlled_geometry_replay':
        raise ValueError('expected controlled geometry replay')
    rows = []
    for episode in data['episodes']:
        steps = episode['steps']
        errors = [s['error'] for s in steps if s.get('error') is not None]
        moves = [s for s in steps if s['action'].startswith('Move')]
        stops = [s for s in steps if s['feedback'] in ('WORSENING', 'NO_PROGRESS', 'SCENE_CHANGED')]
        rows.append(dict(scene=episode['scene'], scenario=episode['scenario'],
            observation_count=len(steps), directed_observations=len(moves),
            first_measured_error=errors[0] if errors else None,
            last_measured_error=errors[-1] if errors else None,
            first_stop_frame=stops[0]['frame_id'] if stops else None,
            final_ready=episode['final_ready'], expected_final_ready=episode['expected_final_ready'],
            ever_ready=episode.get('ever_ready', False)))
    return dict(schema_version=1, evidence_kind=data['evidence_kind'],
        human_aesthetic_improvement_verified=False, physical_camera_motion_verified=False,
        episodes=len(rows), final_state_matches=sum(r['final_ready']==r['expected_final_ready'] for r in rows),
        note='Direction coverage is per simulated observation, not real-world scene coverage. '
             'Empty no-target traces assert final state only. Errors are geometry, not beauty scores.',
        results=rows)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args=parser.parse_args()
    result=summarize(json.loads(args.input.read_text(encoding='utf-8')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
