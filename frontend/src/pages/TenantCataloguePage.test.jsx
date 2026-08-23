import { render, screen } from '@testing-library/react';
import TenantCataloguePage from './TenantCataloguePage';

jest.mock('react-router-dom', () => {
  const React = require('react');
  return {
    Link: ({ children }) => React.createElement('a', null, children),
    useNavigate: () => jest.fn(),
    useParams: () => ({ catalogueId: '42' }),
  };
}, { virtual: true });

jest.mock('../api/catalogueApi', () => ({
  getCataloguePreview: jest.fn(),
  updateCatalogue: jest.fn(),
}));

jest.mock('../api/session', () => ({
  getSession: () => ({ tenantId: 'acme', tenantSchema: 'acme' }),
}));

const { getCataloguePreview } = require('../api/catalogueApi');

test('renders catalogue semantic fields already present in the preview API', async () => {
  getCataloguePreview.mockResolvedValue({
    id: 42,
    clientId: 'acme',
    schemaName: 'PUBLIC',
    databaseName: 'ANALYTICS',
    status: 'DRAFT',
    tableCount: 1,
    columnCount: 1,
    createdAt: '2026-08-14T10:00:00',
    updatedAt: '2026-08-14T10:00:00',
    businessProfile: 'Event-level activity for analysis.',
    entities: JSON.stringify([{ name: 'event', description: 'One recorded activity', grain: 'event' }]),
    globalCaveats: JSON.stringify(['Do not treat event id as a user id']),
    lineageKey: 'lineage-1',
    tables: [
      {
        tableSchema: 'PUBLIC',
        tableName: 'FACT_EVENTS',
        description: 'Event-level activity for analysis.',
        columnCount: 1,
        columns: [
          {
            columnName: 'EVENT_ID',
            dataType: 'NUMBER(38,0)',
            role: 'identifier',
            description: 'Unique event identifier.',
            businessMeaning: 'Identifies a single recorded activity row. Use to count distinct events.',
            aggregationMethod: 'COUNT_DISTINCT',
            synonyms: '["event key"]',
            valueMeanings: '{}',
            confidence: 0.9,
            displayUnit: 'count',
            grainLabel: 'events',
          },
        ],
      },
    ],
  });

  render(<TenantCataloguePage />);

  expect(await screen.findByText('Business profile')).toBeInTheDocument();
  expect(screen.getAllByText('Event-level activity for analysis.').length).toBeGreaterThan(0);
  expect(screen.getByText(/Status: DRAFT/)).toBeInTheDocument();
  expect(screen.getByText(/Lineage: lineage-1/)).toBeInTheDocument();
  expect(screen.getByText('EVENT_ID')).toBeInTheDocument();
  expect(screen.getByText('NUMBER(38,0)')).toBeInTheDocument();
  expect(screen.getByText('Role: identifier')).toBeInTheDocument();
  expect(screen.getByText('Unique event identifier.')).toBeInTheDocument();
  expect(screen.getByText(/Identifies a single recorded activity row/)).toBeInTheDocument();
  expect(screen.getByText('Aggregation: COUNT_DISTINCT')).toBeInTheDocument();
  expect(screen.getByText('Synonyms: event key')).toBeInTheDocument();
  expect(screen.getByText('Display unit: count')).toBeInTheDocument();
  expect(screen.getByText('Grain: events')).toBeInTheDocument();
  expect(screen.getByText('Entities')).toBeInTheDocument();
  expect(screen.getByText('Global caveats')).toBeInTheDocument();
});
