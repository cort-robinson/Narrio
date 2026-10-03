export const baselineVersion = '1.1.0';
export const commitOptions = { preset: 'conventionalcommits' };
export const notesOptions = {
  preset: 'conventionalcommits',
  presetConfig: {
    types: [
      { type: 'feat', section: 'Features' },
      { type: 'fix', section: 'Bug fixes' },
      { type: 'perf', section: 'Performance' },
      ...['chore', 'docs', 'test', 'ci', 'build', 'style', 'refactor', 'revert'].map(type => ({ type, section: 'Maintenance', hidden: true })),
    ],
  },
};
