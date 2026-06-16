import React from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Legend, ReferenceLine
} from 'recharts';

interface LatencyData {
  service: string;
  p50: number;
  p95: number;
  p99: number;
}

interface Props {
  data: LatencyData[];
}

/**
 * Latency heatmap showing P50/P95/P99 across services.
 * Slow chains are highlighted when P99 > 2000ms.
 */
export const LatencyHeatmap: React.FC<Props> = ({ data }) => {
  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">Latency Percentiles (ms)</h2>
      <ResponsiveContainer width="100%" height={280}>
        <BarChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 30 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
          <XAxis
            dataKey="service"
            stroke="#9CA3AF"
            tick={{ fontSize: 10, fill: '#9CA3AF' }}
            angle={-30}
            textAnchor="end"
          />
          <YAxis stroke="#9CA3AF" tick={{ fontSize: 11 }} unit="ms" />
          <Tooltip
            contentStyle={{ backgroundColor: '#1F2937', border: 'none', borderRadius: 8 }}
            labelStyle={{ color: '#E5E7EB' }}
            formatter={(value: number) => [`${value}ms`]}
          />
          <Legend wrapperStyle={{ color: '#9CA3AF', fontSize: 12 }} />
          <ReferenceLine y={2000} stroke="#EF4444" strokeDasharray="3 3" />
          <Bar dataKey="p50" name="P50" fill="#34D399" radius={[2, 2, 0, 0]} />
          <Bar dataKey="p95" name="P95" fill="#FBBF24" radius={[2, 2, 0, 0]} />
          <Bar
            dataKey="p99"
            name="P99"
            fill="#F87171"
            radius={[2, 2, 0, 0]}
            // Highlight slow chains
            label={(props: any) => {
              if (props.value > 2000) {
                return (
                  <text x={props.x + props.width / 2} y={props.y - 5} fill="#EF4444" fontSize={9} textAnchor="middle">
                    SLOW
                  </text>
                );
              }
              return null;
            }}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
};
