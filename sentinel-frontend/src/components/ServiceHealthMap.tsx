import React from 'react';
import { ServiceHealth, ServiceStatus } from '../types';

interface Props {
  services: ServiceHealth[];
}

const statusColor: Record<ServiceStatus, string> = {
  GREEN: 'bg-green-500',
  YELLOW: 'bg-yellow-400',
  RED: 'bg-red-600',
};

const statusBorder: Record<ServiceStatus, string> = {
  GREEN: 'border-green-600',
  YELLOW: 'border-yellow-500',
  RED: 'border-red-700',
};

/**
 * Color-coded service health grid.
 * Green = healthy, Yellow = degraded, Red = down/critical.
 */
export const ServiceHealthMap: React.FC<Props> = ({ services }) => {
  return (
    <div className="p-4 bg-gray-900 rounded-lg">
      <h2 className="text-white text-lg font-semibold mb-4">Service Health Map</h2>
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-3">
        {services.map((service) => (
          <div
            key={service.serviceName}
            className={`rounded-lg border-2 p-3 cursor-pointer transition-transform hover:scale-105 ${statusBorder[service.status]}`}
          >
            <div className={`w-3 h-3 rounded-full mb-2 ${statusColor[service.status]}`} />
            <p className="text-white text-xs font-medium truncate">{service.serviceName}</p>
            <p className="text-gray-400 text-xs mt-1">
              {service.errorRate != null ? `ERR: ${service.errorRate.toFixed(2)}%` : ''}
            </p>
            <p className="text-gray-400 text-xs">
              {service.latencyP95 != null ? `P95: ${service.latencyP95.toFixed(0)}ms` : ''}
            </p>
          </div>
        ))}
        {services.length === 0 && (
          <div className="col-span-full text-gray-500 text-center py-8">
            No services registered yet. Health probes will populate this map.
          </div>
        )}
      </div>
    </div>
  );
};
