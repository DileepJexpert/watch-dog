import React from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine, Legend
} from 'recharts';

interface DataPoint {
  time: string;
  errorRate: number;
  anomalyBandUpper?: number;
  anomalyBandLower?: number;
}

interface Props {
  serviceName: string;
  data: DataPoint[];
}

/**
 * Rolling 1-hour error rate chart with anomaly band overlay.
 */
export const ErrorRateTrend: React.FC<Props> = ({ serviceName, data }) => {
  const hasAnomalyBand = data.some((d) => d.anomalyBandUpper !== undefined);

  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">
        Error Rate Trend — {serviceName}
      </h2>
      <ResponsiveContainer width="100%" height={250}>
        <LineChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#374151" />
          <XAxis dataKey="time" stroke="#9CA3AF" tick={{ fontSize: 11 }} />
          <YAxis stroke="#9CA3AF" tick={{ fontSize: 11 }} unit="%" />
          <Tooltip
            contentStyle={{ backgroundColor: '#1F2937', border: 'none', borderRadius: 8 }}
            labelStyle={{ color: '#E5E7EB' }}
            formatter={(value: number) => [`${value.toFixed(2)}%`]}
          />
          <Legend wrapperStyle={{ color: '#9CA3AF', fontSize: 12 }} />
          <ReferenceLine y={5} stroke="#EF4444" strokeDasharray="3 3" label={{ value: '5% threshold', fill: '#EF4444', fontSize: 11 }} />
          {hasAnomalyBand && (
            <>
              <Line dataKey="anomalyBandUpper" stroke="#6B7280" dot={false} strokeDasharray="5 5" name="Anomaly Upper" />
              <Line dataKey="anomalyBandLower" stroke="#6B7280" dot={false} strokeDasharray="5 5" name="Anomaly Lower" />
            </>
          )}
          <Line
            type="monotone"
            dataKey="errorRate"
            stroke="#F87171"
            dot={false}
            strokeWidth={2}
            name="Error Rate"
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
};
