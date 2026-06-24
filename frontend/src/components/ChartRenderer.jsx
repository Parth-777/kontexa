import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import CorrelationAnalysisCard from './CorrelationAnalysisCard';
import {
  formatMetricValue,
  resolveChartValueFormat,
} from '../utils/semanticMetricFormatter';

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ef4444', '#06b6d4', '#ec4899', '#84cc16'];

function pickKey(row, primary, fallback) {
  if (!row || typeof row !== 'object') return undefined;
  if (primary && row[primary] != null) return row[primary];
  if (fallback && row[fallback] != null) return row[fallback];
  return undefined;
}

function buildChartData(spec) {
  const rows = Array.isArray(spec?.data) ? spec.data : [];
  const type = String(spec?.type || 'BAR').toUpperCase();

  if (type === 'LINE') {
    const xKey = spec.xKey || spec.categoryKey || 'x';
    const yKey = spec.yKey || spec.valueKey || 'y';
    return rows.map((row, idx) => ({
      name: String(pickKey(row, xKey, 'name') ?? idx + 1),
      value: Number(pickKey(row, yKey, 'value')),
      raw: row,
    })).filter((d) => Number.isFinite(d.value));
  }

  const categoryKey = spec.categoryKey || spec.xKey || 'category';
  const valueKey = spec.valueKey || spec.yKey || 'value';
  return rows.map((row, idx) => ({
    name: String(pickKey(row, categoryKey, 'name') ?? `#${idx + 1}`),
    value: Number(pickKey(row, valueKey, 'value')),
    raw: row,
  })).filter((d) => Number.isFinite(d.value));
}

function ChartTooltip({ active, payload, valueFormat, columnKey }) {
  if (!active || !payload?.length) return null;
  const item = payload[0];
  return (
    <div className="chart-tooltip">
      <strong>{item.payload.name}</strong>
      <span>{formatMetricValue(item.value, valueFormat || columnKey, { tooltip: true })}</span>
    </div>
  );
}

export default function ChartRenderer({ spec }) {
  if (!spec) return null;

  const type = String(spec.type || 'BAR').toUpperCase();
  const data = buildChartData(spec);
  const valueFormat = resolveChartValueFormat(spec);
  const valueColumnKey = spec.valueKey || spec.yKey || 'value';
  const formatTick = (v) => formatMetricValue(v, valueFormat);
  const tooltip = (
    <ChartTooltip valueFormat={valueFormat} columnKey={valueColumnKey} />
  );

  if (type === 'CORRELATION') {
    const row = Array.isArray(spec.data) && spec.data.length > 0 ? spec.data[0] : {};
    return (
      <CorrelationAnalysisCard
        analysis={{
          title: spec.title,
          summary: spec.subtitle,
          correlation_coefficient: row.coefficient,
          sample_size: row.sample_size,
          strength: row.strength,
          direction: row.direction,
          source_variable: row.source,
          target_variable: row.target,
          business_interpretation: spec.subtitle,
        }}
        chartData={row}
      />
    );
  }

  if (data.length === 0) {
    return <p className="charts-state">No chart data available.</p>;
  }

  const isHorizontal = type === 'HBAR';
  const isHistogram = type === 'HISTOGRAM';
  const isGrouped = type === 'GROUPED_BAR';
  const isVerticalBar = type === 'BAR' || isHistogram || isGrouped;

  if (type === 'LINE') {
    return (
      <div className="chart-pro">
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={data} margin={{ top: 12, right: 16, left: 8, bottom: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" />
            <XAxis
              dataKey="name"
              tick={{ fill: '#8892aa', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: '#8892aa', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
              tickFormatter={formatTick}
              width={72}
            />
            <Tooltip content={tooltip} />
            <Legend wrapperStyle={{ color: '#8892aa', fontSize: 12 }} />
            <Line
              type="monotone"
              dataKey="value"
              name={spec.title || 'Value'}
              stroke="#3b82f6"
              strokeWidth={2.5}
              dot={{ r: 4, fill: '#3b82f6', strokeWidth: 0 }}
              activeDot={{ r: 6 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    );
  }

  if (type === 'DONUT') {
    return (
      <div className="chart-pro chart-pro--donut">
        <ResponsiveContainer width="100%" height={280}>
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              cx="50%"
              cy="50%"
              innerRadius={72}
              outerRadius={108}
              paddingAngle={2}
              label={({ name, percent }) => `${name} (${(percent * 100).toFixed(0)}%)`}
              labelLine={{ stroke: 'rgba(255,255,255,0.2)' }}
            >
              {data.map((entry, index) => (
                <Cell key={entry.name} fill={COLORS[index % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip content={tooltip} />
            <Legend wrapperStyle={{ color: '#8892aa', fontSize: 12 }} />
          </PieChart>
        </ResponsiveContainer>
      </div>
    );
  }

  if (isHorizontal) {
    return (
      <div className="chart-pro">
        <ResponsiveContainer width="100%" height={Math.max(240, data.length * 36)}>
          <BarChart data={data} layout="vertical" margin={{ top: 8, right: 24, left: 8, bottom: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" horizontal={false} />
            <XAxis
              type="number"
              tick={{ fill: '#8892aa', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
              tickFormatter={formatTick}
            />
            <YAxis
              type="category"
              dataKey="name"
              width={120}
              tick={{ fill: '#c8d0e0', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
            />
            <Tooltip content={tooltip} />
            <Bar dataKey="value" name={spec.title || 'Value'} radius={[0, 6, 6, 0]} maxBarSize={28}>
              {data.map((entry, index) => (
                <Cell key={entry.name} fill={COLORS[index % COLORS.length]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    );
  }

  if (isVerticalBar) {
    return (
      <div className="chart-pro">
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={data} margin={{ top: 12, right: 16, left: 8, bottom: 8 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" vertical={false} />
            <XAxis
              dataKey="name"
              tick={{ fill: '#8892aa', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: '#8892aa', fontSize: 11 }}
              axisLine={{ stroke: 'rgba(255,255,255,0.12)' }}
              tickLine={false}
              tickFormatter={formatTick}
              width={72}
            />
            <Tooltip content={tooltip} />
            <Bar dataKey="value" name={spec.title || 'Value'} radius={[6, 6, 0, 0]} maxBarSize={isHistogram ? 48 : 36}>
              {data.map((entry, index) => (
                <Cell key={entry.name} fill={COLORS[index % COLORS.length]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    );
  }

  return (
    <div className="chart-pro">
      <ResponsiveContainer width="100%" height={Math.max(240, data.length * 36)}>
        <BarChart data={data} layout="vertical" margin={{ top: 8, right: 24, left: 8, bottom: 8 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" horizontal={false} />
          <XAxis type="number" tick={{ fill: '#8892aa', fontSize: 11 }} tickFormatter={formatTick} />
          <YAxis type="category" dataKey="name" width={120} tick={{ fill: '#c8d0e0', fontSize: 11 }} />
          <Tooltip content={tooltip} />
          <Bar dataKey="value" radius={[0, 6, 6, 0]} maxBarSize={28}>
            {data.map((entry, index) => (
              <Cell key={entry.name} fill={COLORS[index % COLORS.length]} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
